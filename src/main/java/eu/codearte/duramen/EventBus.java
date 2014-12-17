package eu.codearte.duramen;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import eu.codearte.duramen.config.EvenBusContext;
import eu.codearte.duramen.event.Event;
import eu.codearte.duramen.handler.EventHandler;
import org.nustaq.serialization.simpleapi.DefaultCoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.invoke.MethodHandles;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Main Duramen class implementing EventBus pattern
 *
 * @author Jakub Kubrynski
 */
@Component
public class EventBus {

	private final static Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private final EvenBusContext evenBusContext;

	private final Multimap<String, EventHandler> handlers;
	private final Semaphore semaphore;

	private DefaultCoder defaultCoder = new DefaultCoder();

	@Autowired
	public EventBus(EvenBusContext evenBusContext) {
		this.evenBusContext = evenBusContext;
		semaphore = new Semaphore(evenBusContext.getMaxMessageCount(), true);
		handlers = HashMultimap.create();
	}

	/**
	 * This method can be used to register custom handler not included in Spring application context
	 *
	 * @param eventDiscriminator full qualified name of event class
	 * @param eventHandler       {@link eu.codearte.duramen.handler.EventHandler} instance
	 */
	public void register(String eventDiscriminator, EventHandler eventHandler) {
		if (checkEventClassCorrectness(eventDiscriminator)) {
			handlers.put(eventDiscriminator, eventHandler);
		}
	}

	/**
	 * Checks if class contains default constructor required by Kryo deserializer
	 *
	 * @param eventDiscriminator full qualified name of event class
	 * @return true if class is correct event class
	 */
	private boolean checkEventClassCorrectness(String eventDiscriminator) {
		try {
			Class.forName(eventDiscriminator).getDeclaredConstructor();
			return true;
		} catch (NoSuchMethodException e) {
			LOG.error("Event {} does not contain default (even private) constructor!", eventDiscriminator);
		} catch (ClassNotFoundException e) {
			LOG.error("Could not retrieve event class {}", eventDiscriminator);
		}
		return false;
	}

	/**
	 * Method used to publish event. Event will be persisted and then processed
	 * in {@link java.util.concurrent.ExecutorService}
	 * After successful processing event will be deleted from persistent store
	 *
	 * @param event event to process
	 */
	@SuppressWarnings("unchecked")
	public void publish(final Event event) {
		checkNotNull(event);
		semaphore.acquireUninterruptibly();
		final Long eventId;
		synchronized (defaultCoder) {
			eventId = evenBusContext.getDatastore().saveEvent(defaultCoder.toByteArray(event));
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("Publishing event [id = {}, body={}]",
					eventId, evenBusContext.getEventJsonSerializer().serializeToJson(event));
		}

		evenBusContext.getExecutorService().submit(new Runnable() {
			@Override
			public void run() {
				processEvent(eventId, event);
			}
		});
	}

	@SuppressWarnings("unchecked")
	private void processEvent(Long eventId, Event event) {
		checkNotNull(event);
		Collection<EventHandler> eventHandlers = handlers.get(event.getClass().getCanonicalName());
		if (LOG.isDebugEnabled()) {
			LOG.debug("Processing event id = {} - found {} valid handlers", eventId, eventHandlers.size());
		}
		for (EventHandler handler : eventHandlers) {
			try {
				handler.onEvent(event);
			} catch (Throwable e) {
				evenBusContext.getExceptionHandler().handleException(event, e, handler);
			}
		}
		evenBusContext.getDatastore().deleteEvent(eventId);
		semaphore.release();
	}

	/**
	 * This method process all event persisted and not processed before application restart.
	 */
	@SuppressWarnings("unchecked")
	public void processSavedEvents() {
		Map<Long, byte[]> events = evenBusContext.getDatastore().getStoredEvents();
		LOG.info("Processing stored events. Found {} events to process", events.size());
		semaphore.acquireUninterruptibly(events.size());
		for (Long eventId : events.keySet()) {
			Object eventToProcess;
			synchronized (defaultCoder) {
				eventToProcess = defaultCoder.toObject(events.get(eventId));
			}
			processEvent(eventId, (Event) eventToProcess);
		}
	}
}
