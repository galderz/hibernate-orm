/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.engine.spi;

import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.hibernate.persister.entity.EntityPersister;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An open-addressing hash map specialised for {@link EntityKey} lookups.
 * <p>
 * Unlike the chaining-based {@link EntityKeyMap}, this implementation stores
 * key-part fields and values directly in <b>parallel arrays</b>, eliminating
 * per-entry {@code Node} object allocations entirely.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>Six parallel arrays: {@code hashes}, {@code rootEntityNames},
 *       {@code identifiers}, {@code changesetIds}, {@code persisters},
 *       {@code values}.</li>
 *   <li>Linear probing for collision resolution.</li>
 *   <li>Tombstone-based deletion ({@link #TOMBSTONE}) to maintain probe
 *       chain integrity.</li>
 *   <li>Load factor 0.75 (same as {@code java.util.HashMap}).</li>
 *   <li>Power-of-two table size for fast modular indexing.</li>
 * </ul>
 *
 * <h2>Memory per entry (vs EntityKeyMap)</h2>
 * <ul>
 *   <li><b>EntityKeyMap.Node</b>: 40 bytes (16-byte header + 24 payload)</li>
 *   <li><b>EntityKeyOpenMap</b>: ~28 bytes in arrays (4 × oop + 1 × int + 1 × oop for value),
 *       no per-entry object header. At 75% load: ~37 bytes/slot amortised.</li>
 * </ul>
 *
 * @param <V> the value type
 */
public class EntityKeyOpenMap<V> {

	// =========================================================================
	// Constants
	// =========================================================================

	private static final int DEFAULT_INITIAL_CAPACITY = 16;
	private static final int MAXIMUM_CAPACITY = 1 << 30;
	private static final float DEFAULT_LOAD_FACTOR = 0.75f;

	/**
	 * Sentinel in {@code rootEntityNames[i]} indicating a deleted slot.
	 * Probing must skip tombstones during lookup but may reuse them on insert.
	 */
	private static final String TOMBSTONE = new String( "\0TOMBSTONE\0" );

	// =========================================================================
	// Parallel arrays — replace HashMap.Node / EntityKeyMap.Node
	// =========================================================================

	/** Pre-computed hash (same formula as EntityKeyMap.hash). */
	int[] hashes;
	/** Entity-type discriminator (root entity name). null = empty slot. */
	@Nullable String[] rootEntityNames;
	/** Entity identifier. */
	@Nullable Object[] identifiers;
	/** Temporal changeset id (usually null). */
	@Nullable Object[] changesetIds;
	/** Persister reference (for reconstructing EntityKey on iteration). */
	@Nullable EntityPersister[] persisters;
	/** Mapped values. */
	@Nullable Object[] values;

	int size;
	int tombstoneCount;
	int modCount;
	int threshold;
	final float loadFactor;

	private transient @Nullable Values valuesView;
	private transient @Nullable EntrySet entrySetView;

	// =========================================================================
	// Constructors
	// =========================================================================

	public EntityKeyOpenMap() {
		this( DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR );
	}

	public EntityKeyOpenMap(int initialCapacity) {
		this( initialCapacity, DEFAULT_LOAD_FACTOR );
	}

	@SuppressWarnings("unchecked")
	public EntityKeyOpenMap(int initialCapacity, float loadFactor) {
		if ( initialCapacity < 0 ) {
			throw new IllegalArgumentException( "Illegal initial capacity: " + initialCapacity );
		}
		if ( initialCapacity > MAXIMUM_CAPACITY ) {
			initialCapacity = MAXIMUM_CAPACITY;
		}
		this.loadFactor = loadFactor;
		final int cap = tableSizeFor( initialCapacity );
		this.hashes = new int[cap];
		this.rootEntityNames = new String[cap];
		this.identifiers = new Object[cap];
		this.changesetIds = new Object[cap];
		this.persisters = new EntityPersister[cap];
		this.values = new Object[cap];
		this.threshold = (int) ( cap * loadFactor );
	}

	static int tableSizeFor(int cap) {
		int n = -1 >>> Integer.numberOfLeadingZeros( cap - 1 );
		return ( n < 0 ) ? 1 : ( n >= MAXIMUM_CAPACITY ) ? MAXIMUM_CAPACITY : n + 1;
	}

	// =========================================================================
	// Hash & equality — same logic as EntityKeyMap
	// =========================================================================

	static int hash(String rootEntityName, Object identifier, @Nullable Object changesetId) {
		int h = rootEntityName.hashCode();
		h = 37 * h + identifier.hashCode();
		if ( changesetId != null ) {
			h = 37 * h + changesetId.hashCode();
		}
		return h ^ ( h >>> 16 );
	}

	// =========================================================================
	// Size / empty
	// =========================================================================

	public int size() {
		return size;
	}

	public boolean isEmpty() {
		return size == 0;
	}

	// =========================================================================
	// Public API — same signatures as EntityKeyMap
	// =========================================================================

	public @Nullable V get(EntityPersister persister, EntityKey key) {
		final String rootName = persister.getRootEntityName();
		final Object id = key.getIdentifier();
		final @Nullable Object csId = key.getChangesetId();
		final int h = hash( rootName, id, csId );
		final int idx = findSlot( h, rootName, id, csId );
		if ( idx < 0 ) {
			return null;
		}
		@SuppressWarnings("unchecked")
		final V v = (V) values[idx];
		return v;
	}

	public @Nullable V put(EntityPersister persister, EntityKey key, V value) {
		final String rootName = persister.getRootEntityName();
		final Object id = key.getIdentifier();
		final @Nullable Object csId = key.getChangesetId();
		final int h = hash( rootName, id, csId );
		return putVal( h, rootName, id, csId, persister, value, false );
	}

	public @Nullable V putIfAbsent(EntityPersister persister, EntityKey key, V value) {
		final String rootName = persister.getRootEntityName();
		final Object id = key.getIdentifier();
		final @Nullable Object csId = key.getChangesetId();
		final int h = hash( rootName, id, csId );
		return putVal( h, rootName, id, csId, persister, value, true );
	}

	public @Nullable V remove(EntityPersister persister, EntityKey key) {
		final String rootName = persister.getRootEntityName();
		final Object id = key.getIdentifier();
		final @Nullable Object csId = key.getChangesetId();
		final int h = hash( rootName, id, csId );
		final int idx = findSlot( h, rootName, id, csId );
		if ( idx < 0 ) {
			return null;
		}
		@SuppressWarnings("unchecked")
		final V old = (V) values[idx];
		removeAt( idx );
		return old;
	}

	public boolean containsKey(EntityPersister persister, EntityKey key) {
		final String rootName = persister.getRootEntityName();
		final Object id = key.getIdentifier();
		final @Nullable Object csId = key.getChangesetId();
		return findSlot( hash( rootName, id, csId ), rootName, id, csId ) >= 0;
	}

	// =========================================================================
	// Internal: find slot — linear probing
	// =========================================================================

	/**
	 * Find the slot containing the given key, or return -1.
	 */
	@SuppressWarnings("nullness")
	private int findSlot(int h, String rootEntityName, Object identifier, @Nullable Object changesetId) {
		final int mask = hashes.length - 1;
		int idx = h & mask;
		while ( true ) {
			final String ren = rootEntityNames[idx];
			if ( ren == null ) {
				return -1; // empty slot — key not present
			}
			if ( ren != TOMBSTONE
					&& hashes[idx] == h
					&& ren.equals( rootEntityName )
					&& identifiers[idx].equals( identifier )
					&& Objects.equals( changesetIds[idx], changesetId ) ) {
				return idx;
			}
			idx = ( idx + 1 ) & mask;
		}
	}

	// =========================================================================
	// Internal: put — linear probing with tombstone reuse
	// =========================================================================

	@SuppressWarnings({"unchecked", "nullness"})
	private @Nullable V putVal(int h, String rootEntityName, Object identifier,
			@Nullable Object changesetId, EntityPersister persister,
			V value, boolean onlyIfAbsent) {
		final int mask = hashes.length - 1;
		int idx = h & mask;
		int firstTombstone = -1;
		while ( true ) {
			final String ren = rootEntityNames[idx];
			if ( ren == null ) {
				// empty slot — insert here (or at earlier tombstone)
				final int insertIdx = firstTombstone >= 0 ? firstTombstone : idx;
				hashes[insertIdx] = h;
				rootEntityNames[insertIdx] = rootEntityName;
				identifiers[insertIdx] = identifier;
				changesetIds[insertIdx] = changesetId;
				persisters[insertIdx] = persister;
				values[insertIdx] = value;
				if ( firstTombstone >= 0 ) {
					tombstoneCount--;
				}
				++modCount;
				if ( ++size > threshold ) {
					resize();
				}
				return null;
			}
			if ( ren == TOMBSTONE ) {
				if ( firstTombstone < 0 ) {
					firstTombstone = idx;
				}
			}
			else if ( hashes[idx] == h
					&& ren.equals( rootEntityName )
					&& identifiers[idx].equals( identifier )
					&& Objects.equals( changesetIds[idx], changesetId ) ) {
				// existing key
				final V old = (V) values[idx];
				if ( !onlyIfAbsent || old == null ) {
					values[idx] = value;
					persisters[idx] = persister; // refresh persister
				}
				return old;
			}
			idx = ( idx + 1 ) & mask;
		}
	}

	// =========================================================================
	// Internal: remove — tombstone
	// =========================================================================

	@SuppressWarnings("nullness")
	private void removeAt(int idx) {
		rootEntityNames[idx] = TOMBSTONE;
		identifiers[idx] = null;
		changesetIds[idx] = null;
		persisters[idx] = null;
		values[idx] = null;
		hashes[idx] = 0;
		++modCount;
		--size;
		++tombstoneCount;
		// If tombstones accumulate too much, resize to clean them up
		if ( tombstoneCount > ( hashes.length >>> 2 ) ) { // > 25% tombstones
			resize();
		}
	}

	// =========================================================================
	// Resize — rehash into a fresh table (cleans tombstones)
	// =========================================================================

	@SuppressWarnings({"unchecked", "nullness"})
	private void resize() {
		final int oldCap = hashes.length;
		final int newCap;
		if ( size + 1 > threshold && oldCap < MAXIMUM_CAPACITY ) {
			newCap = oldCap << 1;
		}
		else {
			newCap = oldCap; // just clean tombstones
		}

		final int[] oldHashes = hashes;
		final String[] oldNames = rootEntityNames;
		final Object[] oldIds = identifiers;
		final Object[] oldCsIds = changesetIds;
		final EntityPersister[] oldPersisters = persisters;
		final Object[] oldValues = values;

		hashes = new int[newCap];
		rootEntityNames = new String[newCap];
		identifiers = new Object[newCap];
		changesetIds = new Object[newCap];
		persisters = new EntityPersister[newCap];
		values = new Object[newCap];
		threshold = (int) ( newCap * loadFactor );
		tombstoneCount = 0;

		final int mask = newCap - 1;
		for ( int i = 0; i < oldCap; i++ ) {
			final String ren = oldNames[i];
			if ( ren != null && ren != TOMBSTONE ) {
				final int h = oldHashes[i];
				int idx = h & mask;
				while ( rootEntityNames[idx] != null ) {
					idx = ( idx + 1 ) & mask;
				}
				hashes[idx] = h;
				rootEntityNames[idx] = ren;
				identifiers[idx] = oldIds[i];
				changesetIds[idx] = oldCsIds[i];
				persisters[idx] = oldPersisters[i];
				values[idx] = oldValues[i];
			}
		}
	}

	// =========================================================================
	// clear
	// =========================================================================

	@SuppressWarnings("nullness")
	public void clear() {
		modCount++;
		if ( size > 0 || tombstoneCount > 0 ) {
			final int len = hashes.length;
			for ( int i = 0; i < len; i++ ) {
				hashes[i] = 0;
				rootEntityNames[i] = null;
				identifiers[i] = null;
				changesetIds[i] = null;
				persisters[i] = null;
				values[i] = null;
			}
			size = 0;
			tombstoneCount = 0;
		}
	}

	// =========================================================================
	// Values collection view
	// =========================================================================

	public Collection<V> values() {
		Values vs = valuesView;
		if ( vs == null ) {
			vs = new Values();
			valuesView = vs;
		}
		return vs;
	}

	final class Values extends AbstractCollection<V> {
		@Override
		public int size() {
			return size;
		}

		@Override
		public void clear() {
			EntityKeyOpenMap.this.clear();
		}

		@Override
		public Iterator<V> iterator() {
			return new ValueIterator();
		}

		@Override
		public void forEach(Consumer<? super V> action) {
			if ( action == null ) {
				throw new NullPointerException();
			}
			if ( size > 0 ) {
				final int mc = modCount;
				final int len = hashes.length;
				for ( int i = 0; i < len; i++ ) {
					final String ren = rootEntityNames[i];
					if ( ren != null && ren != TOMBSTONE ) {
						@SuppressWarnings("unchecked")
						final V v = (V) values[i];
						action.accept( v );
					}
				}
				if ( modCount != mc ) {
					throw new ConcurrentModificationException();
				}
			}
		}
	}

	// =========================================================================
	// Entry set
	// =========================================================================

	public static final class Entry<V> {
		private final String rootEntityName;
		private final Object identifier;
		private final @Nullable Object changesetId;
		private final EntityPersister persister;
		private V value;

		Entry(String rootEntityName, Object identifier, @Nullable Object changesetId,
				EntityPersister persister, V value) {
			this.rootEntityName = rootEntityName;
			this.identifier = identifier;
			this.changesetId = changesetId;
			this.persister = persister;
			this.value = value;
		}

		public EntityKey getKey() {
			return changesetId != null
					? EntityKey.of( identifier, persister, changesetId )
					: EntityKey.of( identifier, persister );
		}

		public String getRootEntityName() {
			return rootEntityName;
		}

		public Object getIdentifier() {
			return identifier;
		}

		public @Nullable Object getChangesetId() {
			return changesetId;
		}

		public EntityPersister getPersister() {
			return persister;
		}

		public V getValue() {
			return value;
		}

		public V setValue(V newValue) {
			V old = value;
			value = newValue;
			return old;
		}
	}

	public Set<Entry<V>> entrySet() {
		EntrySet es = entrySetView;
		if ( es == null ) {
			es = new EntrySet();
			entrySetView = es;
		}
		return es;
	}

	final class EntrySet extends AbstractSet<Entry<V>> {
		@Override
		public int size() {
			return size;
		}

		@Override
		public void clear() {
			EntityKeyOpenMap.this.clear();
		}

		@Override
		public Iterator<Entry<V>> iterator() {
			return new EntryIterator();
		}

		@Override
		@SuppressWarnings("nullness")
		public void forEach(Consumer<? super Entry<V>> action) {
			if ( action == null ) {
				throw new NullPointerException();
			}
			if ( size > 0 ) {
				final int mc = modCount;
				final int len = hashes.length;
				for ( int i = 0; i < len; i++ ) {
					final String ren = rootEntityNames[i];
					if ( ren != null && ren != TOMBSTONE ) {
						@SuppressWarnings("unchecked")
						final V v = (V) values[i];
						action.accept( new Entry<>( ren, identifiers[i],
								changesetIds[i], persisters[i], v ) );
					}
				}
				if ( modCount != mc ) {
					throw new ConcurrentModificationException();
				}
			}
		}
	}

	// =========================================================================
	// forEach convenience
	// =========================================================================

	@SuppressWarnings("nullness")
	public void forEach(BiConsumer<EntityKey, V> action) {
		if ( action == null ) {
			throw new NullPointerException();
		}
		if ( size > 0 ) {
			final int mc = modCount;
			final int len = hashes.length;
			for ( int i = 0; i < len; i++ ) {
				final String ren = rootEntityNames[i];
				if ( ren != null && ren != TOMBSTONE ) {
					final EntityKey ek = changesetIds[i] != null
							? EntityKey.of( identifiers[i], persisters[i], changesetIds[i] )
							: EntityKey.of( identifiers[i], persisters[i] );
					@SuppressWarnings("unchecked")
					final V v = (V) values[i];
					action.accept( ek, v );
				}
			}
			if ( modCount != mc ) {
				throw new ConcurrentModificationException();
			}
		}
	}

	@SuppressWarnings("nullness")
	public void forEachRaw(RawEntryConsumer<V> action) {
		if ( action == null ) {
			throw new NullPointerException();
		}
		if ( size > 0 ) {
			final int mc = modCount;
			final int len = hashes.length;
			for ( int i = 0; i < len; i++ ) {
				final String ren = rootEntityNames[i];
				if ( ren != null && ren != TOMBSTONE ) {
					@SuppressWarnings("unchecked")
					final V v = (V) values[i];
					action.accept( ren, identifiers[i], changesetIds[i], v );
				}
			}
			if ( modCount != mc ) {
				throw new ConcurrentModificationException();
			}
		}
	}

	@FunctionalInterface
	public interface RawEntryConsumer<V> {
		void accept(String rootEntityName, Object identifier,
				@Nullable Object changesetId, V value);
	}

	// =========================================================================
	// Iterators — skip empty/tombstone slots
	// =========================================================================

	@SuppressWarnings("nullness")
	abstract class OpenIterator {
		int nextIdx;
		int currentIdx = -1;
		int expectedModCount;

		OpenIterator() {
			expectedModCount = modCount;
			nextIdx = advance( 0 );
		}

		private int advance(int from) {
			final int len = hashes.length;
			for ( int i = from; i < len; i++ ) {
				final String ren = rootEntityNames[i];
				if ( ren != null && ren != TOMBSTONE ) {
					return i;
				}
			}
			return -1;
		}

		public final boolean hasNext() {
			return nextIdx >= 0;
		}

		final int nextIndex() {
			if ( modCount != expectedModCount ) {
				throw new ConcurrentModificationException();
			}
			if ( nextIdx < 0 ) {
				throw new NoSuchElementException();
			}
			currentIdx = nextIdx;
			nextIdx = advance( nextIdx + 1 );
			return currentIdx;
		}

		public final void remove() {
			if ( currentIdx < 0 ) {
				throw new IllegalStateException();
			}
			if ( modCount != expectedModCount ) {
				throw new ConcurrentModificationException();
			}
			removeAt( currentIdx );
			currentIdx = -1;
			expectedModCount = modCount;
		}
	}

	final class ValueIterator extends OpenIterator implements Iterator<V> {
		@SuppressWarnings("unchecked")
		@Override
		public V next() {
			return (V) values[nextIndex()];
		}
	}

	final class EntryIterator extends OpenIterator implements Iterator<Entry<V>> {
		@SuppressWarnings({"unchecked", "nullness"})
		@Override
		public Entry<V> next() {
			final int i = nextIndex();
			return new Entry<>( rootEntityNames[i], identifiers[i],
					changesetIds[i], persisters[i], (V) values[i] );
		}
	}

	// =========================================================================
	// toString
	// =========================================================================

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder( "{" );
		boolean first = true;
		final int len = hashes.length;
		for ( int i = 0; i < len; i++ ) {
			final String ren = rootEntityNames[i];
			if ( ren != null && ren != TOMBSTONE ) {
				if ( !first ) {
					sb.append( ", " );
				}
				sb.append( ren ).append( '#' ).append( identifiers[i] );
				if ( changesetIds[i] != null ) {
					sb.append( '@' ).append( changesetIds[i] );
				}
				sb.append( '=' ).append( values[i] );
				first = false;
			}
		}
		return sb.append( "}" ).toString();
	}
}
