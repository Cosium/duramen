package eu.codearte.duramen.datastore;

import eu.codearte.duramen.generator.IdGenerator;
import eu.codearte.duramen.generator.RandomIdGenerator;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation mainly for testing purposes. Does not persist events
 *
 * @author Jakub Kubrynski
 */
public class InMemory implements Datastore {

	private final IdGenerator randomIdGenerator = new RandomIdGenerator();
	private Map<Long, byte[]> hashMap;

	@SuppressWarnings("UnusedDeclaration")
	public InMemory() {
		hashMap = new ConcurrentHashMap<>();
	}

	@Override
	public Long saveEvent(byte[] eventAsBytes) {
		Long id = randomIdGenerator.getNextId();
		hashMap.put(id, eventAsBytes);
		return id;
	}

	@Override
	public void deleteEvent(Long eventId) {
		hashMap.remove(eventId);
	}

	@Override
	public Map<Long, byte[]> getStoredEvents() {
		return new HashMap<>(hashMap);
	}
}