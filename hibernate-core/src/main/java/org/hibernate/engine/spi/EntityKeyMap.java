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

import jakarta.annotation.Nullable;

/**
 * A hash-map implementation specialised for {@link EntityKey} lookups in the
 * persistence context.
 * <p>
 * This class is a <b>reimplementation</b> of the core algorithms of
 * {@link java.util.HashMap} (OpenJDK 25) with one critical difference:
 * <em>the entity-type discriminator (root entity name) and the optional
 * temporal changeset-id are stored inside each {@code Node}, not inside
 * the key object.</em>  This allows {@link EntityKey} itself to carry
 * nothing but the bare identifier value ({@link EntityKeyImpl} has a
 * single {@code Object identifier} field).
 *
 * <h2>What is different from {@code java.util.HashMap}</h2>
 * <ul>
 *   <li><b>Node fields:</b> instead of a generic {@code K key}, each
 *       {@code Node} stores three key-part fields:
 *       {@code String rootEntityName}, {@code Object identifier}, and
 *       {@code @Nullable Object changesetId}.</li>
 *   <li><b>Hash function:</b> {@link #hash(String, Object, Object)}
 *       computes the hash from all three key-part fields and applies the
 *       same bit-spread as {@code HashMap.hash()}.</li>
 *   <li><b>Equality:</b> {@link #keysEqual} compares all three key-part
 *       fields; the caller supplies these via an {@link EntityPersister}
 *       and an {@link EntityKey}.</li>
 *   <li><b>Public API:</b> lookup methods take
 *       {@code (EntityPersister, EntityKey)} instead of a single
 *       {@code Object key}.</li>
 *   <li><b>Tree bins:</b> not implemented. Bins use linked lists only.
 *       Entity key collisions (same hash, different logical key) are
 *       extremely rare in practice, so the O(n) worst case per bin is
 *       acceptable and avoids ~500 lines of red-black tree code.</li>
 *   <li><b>Serialization:</b> not supported via Java serialization.
 *       The persistence context handles its own serialization
 *       externally.</li>
 *   <li><b>Map interface:</b> this class does <em>not</em> implement
 *       {@link java.util.Map} because its lookup methods require an
 *       {@code EntityPersister} alongside the key.</li>
 * </ul>
 *
 * <h2>What is identical to {@code java.util.HashMap}</h2>
 * <ul>
 *   <li>Power-of-two bucket array, load factor (0.75), resize strategy,
 *       and capacity constants.</li>
 *   <li>The {@link #resize()} method (preserving order on split).</li>
 *   <li>Fail-fast iterators with {@code modCount} tracking.</li>
 *   <li>{@link #values()} and iteration support.</li>
 * </ul>
 *
 * @param <V> the value type
 */
public class EntityKeyMap<V> {

	// =========================================================================
	// Constants — identical to java.util.HashMap
	// =========================================================================

	static final int DEFAULT_INITIAL_CAPACITY = 1 << 4; // 16
	static final int MAXIMUM_CAPACITY = 1 << 30;
	static final float DEFAULT_LOAD_FACTOR = 0.75f;

	// =========================================================================
	// CUSTOM Node — replaces HashMap.Node<K,V>
	// =========================================================================
	// Difference: instead of a single "K key" field, the node stores three
	// key-part fields that together form the logical key:
	//   - rootEntityName  (from EntityPersister.getRootEntityName())
	//   - identifier      (from EntityKey.getIdentifier())
	//   - changesetId     (from EntityKey.getChangesetId(), nullable)
	// =========================================================================

	static class Node<V> {
		final int hash;
		// --- CUSTOM key-part fields (replaces "final K key" in HashMap.Node) ---
		final String rootEntityName;
		final Object identifier;
		final @Nullable Object changesetId;
		// --- end custom fields ---
		V value;
		Node<V> next;

		Node(int hash, String rootEntityName, Object identifier,
				@Nullable Object changesetId, V value, Node<V> next) {
			this.hash = hash;
			this.rootEntityName = rootEntityName;
			this.identifier = identifier;
			this.changesetId = changesetId;
			this.value = value;
			this.next = next;
		}

		/**
		 * Reconstruct an {@link EntityKey} from this node's key-part fields.
		 */
		EntityKey toEntityKey() {
			return changesetId != null
					? EntityKey.of( identifier, changesetId )
					: EntityKey.of( identifier );
		}

		@Override
		public String toString() {
			return rootEntityName + "#" + identifier
					+ ( changesetId != null ? "@" + changesetId : "" )
					+ "=" + value;
		}
	}

	// =========================================================================
	// CUSTOM hash & equality — replaces HashMap.hash(Object) and
	// the inline "key.equals(k)" checks in HashMap.getNode / putVal / etc.
	// =========================================================================

	/**
	 * Compute the hash for the composite key.
	 * The bit-spread (XOR with unsigned-right-shift) is identical to
	 * {@code java.util.HashMap.hash(Object)}.
	 */
	static int hash(String rootEntityName, Object identifier,
			@Nullable Object changesetId) {
		int h = rootEntityName.hashCode();
		h = 37 * h + identifier.hashCode();
		if ( changesetId != null ) {
			h = 37 * h + changesetId.hashCode();
		}
		// Spread bits — same as HashMap.hash()
		return h ^ ( h >>> 16 );
	}

	/**
	 * Check logical key equality.  Two entries match when all three
	 * key-part fields are equal.
	 */
	static boolean keysEqual(Node<?> node, int hash,
			String rootEntityName, Object identifier,
			@Nullable Object changesetId) {
		return node.hash == hash
				&& node.rootEntityName.equals( rootEntityName )
				&& node.identifier.equals( identifier )
				&& Objects.equals( node.changesetId, changesetId );
	}

	// =========================================================================
	// Fields — identical to java.util.HashMap
	// =========================================================================

	transient Node<V>[] table;
	transient int size;
	transient int modCount;
	int threshold;
	final float loadFactor;

	// Cached collection views
	private transient @Nullable Values valuesView;
	private transient @Nullable EntrySet entrySetView;

	// =========================================================================
	// Constructors — identical to java.util.HashMap
	// =========================================================================

	public EntityKeyMap() {
		this.loadFactor = DEFAULT_LOAD_FACTOR;
	}

	public EntityKeyMap(int initialCapacity) {
		this( initialCapacity, DEFAULT_LOAD_FACTOR );
	}

	public EntityKeyMap(int initialCapacity, float loadFactor) {
		if ( initialCapacity < 0 ) {
			throw new IllegalArgumentException( "Illegal initial capacity: " + initialCapacity );
		}
		if ( initialCapacity > MAXIMUM_CAPACITY ) {
			initialCapacity = MAXIMUM_CAPACITY;
		}
		if ( loadFactor <= 0 || Float.isNaN( loadFactor ) ) {
			throw new IllegalArgumentException( "Illegal load factor: " + loadFactor );
		}
		this.loadFactor = loadFactor;
		this.threshold = tableSizeFor( initialCapacity );
	}

	// =========================================================================
	// Static utility — identical to java.util.HashMap
	// =========================================================================

	static int tableSizeFor(int cap) {
		int n = -1 >>> Integer.numberOfLeadingZeros( cap - 1 );
		return ( n < 0 ) ? 1 : ( n >= MAXIMUM_CAPACITY ) ? MAXIMUM_CAPACITY : n + 1;
	}

	// =========================================================================
	// Size / empty — identical to java.util.HashMap
	// =========================================================================

	public int size() {
		return size;
	}

	public boolean isEmpty() {
		return size == 0;
	}

	// =========================================================================
	// CUSTOM public API — replaces HashMap.get / put / remove / containsKey
	// These methods accept (EntityPersister, EntityKey) and extract the three
	// key-part values before delegating to the internal getNode / putVal /
	// removeNode methods.
	// =========================================================================

	/**
	 * Returns the value for the given persister + entity key, or {@code null}.
	 */
	public @Nullable V get(EntityPersister persister, EntityKey key) {
		final String rootName = persister.getRootEntityName();
		final Object id = key.getIdentifier();
		final Object csId = key.getChangesetId();
		final Node<V> e = getNode( hash( rootName, id, csId ), rootName, id, csId );
		return e == null ? null : e.value;
	}

	/**
	 * Associates the value with the given persister + entity key.
	 *
	 * @return the previous value, or {@code null}
	 */
	public @Nullable V put(EntityPersister persister, EntityKey key, V value) {
		final String rootName = persister.getRootEntityName();
		final Object id = key.getIdentifier();
		final Object csId = key.getChangesetId();
		return putVal( hash( rootName, id, csId ), rootName, id, csId, value, false );
	}

	/**
	 * If the key is not already present, associates it with the given value.
	 *
	 * @return the existing value if present, or {@code null} if the new
	 *         value was inserted
	 */
	public @Nullable V putIfAbsent(EntityPersister persister, EntityKey key, V value) {
		final String rootName = persister.getRootEntityName();
		final Object id = key.getIdentifier();
		final Object csId = key.getChangesetId();
		return putVal( hash( rootName, id, csId ), rootName, id, csId, value, true );
	}

	/**
	 * Removes the mapping for the given persister + entity key.
	 *
	 * @return the removed value, or {@code null}
	 */
	public @Nullable V remove(EntityPersister persister, EntityKey key) {
		final String rootName = persister.getRootEntityName();
		final Object id = key.getIdentifier();
		final Object csId = key.getChangesetId();
		final Node<V> e = removeNode( hash( rootName, id, csId ), rootName, id, csId );
		return e == null ? null : e.value;
	}

	/**
	 * Returns {@code true} if this map contains the given persister + key.
	 */
	public boolean containsKey(EntityPersister persister, EntityKey key) {
		final String rootName = persister.getRootEntityName();
		final Object id = key.getIdentifier();
		final Object csId = key.getChangesetId();
		return getNode( hash( rootName, id, csId ), rootName, id, csId ) != null;
	}

	// =========================================================================
	// Internal lookup — adapted from java.util.HashMap.getNode
	//
	// DIFFERENCE: instead of comparing "key.equals(k)" against a stored key
	// object, we compare the three key-part fields via keysEqual().
	// DIFFERENCE: no TreeNode branch (linked-list only).
	// =========================================================================

	@Nullable Node<V> getNode(int hash, String rootEntityName,
			Object identifier, @Nullable Object changesetId) {
		final Node<V>[] tab = table;
		if ( tab == null ) {
			return null;
		}
		final int n = tab.length;
		if ( n == 0 ) {
			return null;
		}
		Node<V> e = tab[( n - 1 ) & hash];
		while ( e != null ) {
			// --- CUSTOM equality check (replaces key.equals) ---
			if ( keysEqual( e, hash, rootEntityName, identifier, changesetId ) ) {
				return e;
			}
			e = e.next;
		}
		return null;
	}

	// =========================================================================
	// Internal put — adapted from java.util.HashMap.putVal
	//
	// DIFFERENCE: Node constructor takes the three key-part fields.
	// DIFFERENCE: equality via keysEqual() instead of key.equals().
	// DIFFERENCE: no TreeNode / treeifyBin branches.
	// =========================================================================

	@Nullable V putVal(int hash, String rootEntityName, Object identifier,
			@Nullable Object changesetId, V value, boolean onlyIfAbsent) {
		Node<V>[] tab;
		int n;
		if ( ( tab = table ) == null || ( n = tab.length ) == 0 ) {
			n = ( tab = resize() ).length;
		}
		final int i = ( n - 1 ) & hash;
		Node<V> p = tab[i];
		if ( p == null ) {
			// --- CUSTOM: Node created with key-part fields ---
			tab[i] = new Node<>( hash, rootEntityName, identifier, changesetId, value, null );
		}
		else {
			Node<V> e = null;
			// --- CUSTOM equality check (replaces key.equals) ---
			if ( keysEqual( p, hash, rootEntityName, identifier, changesetId ) ) {
				e = p;
			}
			else {
				// --- no TreeNode branch ---
				for ( int binCount = 0; ; ++binCount ) {
					if ( ( e = p.next ) == null ) {
						// --- CUSTOM: Node created with key-part fields ---
						p.next = new Node<>( hash, rootEntityName, identifier, changesetId, value, null );
						break;
					}
					// --- CUSTOM equality check ---
					if ( keysEqual( e, hash, rootEntityName, identifier, changesetId ) ) {
						break;
					}
					p = e;
				}
			}
			if ( e != null ) {
				// existing mapping for key
				final V oldValue = e.value;
				if ( !onlyIfAbsent || oldValue == null ) {
					e.value = value;
				}
				return oldValue;
			}
		}
		++modCount;
		if ( ++size > threshold ) {
			resize();
		}
		return null;
	}

	// =========================================================================
	// Resize — identical to java.util.HashMap.resize (linked-list path only)
	// =========================================================================

	@SuppressWarnings("unchecked")
	Node<V>[] resize() {
		final Node<V>[] oldTab = table;
		final int oldCap = ( oldTab == null ) ? 0 : oldTab.length;
		final int oldThr = threshold;
		int newCap, newThr = 0;
		if ( oldCap > 0 ) {
			if ( oldCap >= MAXIMUM_CAPACITY ) {
				threshold = Integer.MAX_VALUE;
				return oldTab;
			}
			else if ( ( newCap = oldCap << 1 ) < MAXIMUM_CAPACITY
					&& oldCap >= DEFAULT_INITIAL_CAPACITY ) {
				newThr = oldThr << 1;
			}
		}
		else if ( oldThr > 0 ) {
			newCap = oldThr;
		}
		else {
			newCap = DEFAULT_INITIAL_CAPACITY;
			newThr = (int) ( DEFAULT_LOAD_FACTOR * DEFAULT_INITIAL_CAPACITY );
		}
		if ( newThr == 0 ) {
			final float ft = (float) newCap * loadFactor;
			newThr = ( newCap < MAXIMUM_CAPACITY && ft < (float) MAXIMUM_CAPACITY )
					? (int) ft : Integer.MAX_VALUE;
		}
		threshold = newThr;
		final Node<V>[] newTab = (Node<V>[]) new Node<?>[newCap];
		table = newTab;
		if ( oldTab != null ) {
			for ( int j = 0; j < oldCap; ++j ) {
				Node<V> e;
				if ( ( e = oldTab[j] ) != null ) {
					oldTab[j] = null;
					if ( e.next == null ) {
						newTab[e.hash & ( newCap - 1 )] = e;
					}
					else {
						// --- identical to HashMap linked-list split ---
						Node<V> loHead = null, loTail = null;
						Node<V> hiHead = null, hiTail = null;
						Node<V> next;
						do {
							next = e.next;
							if ( ( e.hash & oldCap ) == 0 ) {
								if ( loTail == null ) {
									loHead = e;
								}
								else {
									loTail.next = e;
								}
								loTail = e;
							}
							else {
								if ( hiTail == null ) {
									hiHead = e;
								}
								else {
									hiTail.next = e;
								}
								hiTail = e;
							}
						}
						while ( ( e = next ) != null );
						if ( loTail != null ) {
							loTail.next = null;
							newTab[j] = loHead;
						}
						if ( hiTail != null ) {
							hiTail.next = null;
							newTab[j + oldCap] = hiHead;
						}
					}
				}
			}
		}
		return newTab;
	}

	// =========================================================================
	// Internal remove — adapted from java.util.HashMap.removeNode
	//
	// DIFFERENCE: takes key-part fields instead of Object key.
	// DIFFERENCE: equality via keysEqual().
	// DIFFERENCE: no TreeNode branch.
	// =========================================================================

	@Nullable Node<V> removeNode(int hash, String rootEntityName,
			Object identifier, @Nullable Object changesetId) {
		final Node<V>[] tab = table;
		if ( tab == null ) {
			return null;
		}
		final int n = tab.length;
		if ( n == 0 ) {
			return null;
		}
		final int index = ( n - 1 ) & hash;
		Node<V> p = tab[index];
		if ( p == null ) {
			return null;
		}

		Node<V> node = null;
		// --- CUSTOM equality check ---
		if ( keysEqual( p, hash, rootEntityName, identifier, changesetId ) ) {
			node = p;
		}
		else {
			Node<V> e;
			while ( ( e = p.next ) != null ) {
				if ( keysEqual( e, hash, rootEntityName, identifier, changesetId ) ) {
					node = e;
					break;
				}
				p = e;
			}
		}

		if ( node != null ) {
			// --- identical to HashMap unlink logic (linked-list only) ---
			if ( node == tab[index] ) {
				tab[index] = node.next;
			}
			else {
				p.next = node.next;
			}
			++modCount;
			--size;
			return node;
		}
		return null;
	}

	// =========================================================================
	// clear — identical to java.util.HashMap.clear
	// =========================================================================

	public void clear() {
		final Node<V>[] tab = table;
		modCount++;
		if ( tab != null && size > 0 ) {
			size = 0;
			for ( int i = 0; i < tab.length; ++i ) {
				tab[i] = null;
			}
		}
	}

	// =========================================================================
	// Values collection view — identical structure to java.util.HashMap.Values
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
			EntityKeyMap.this.clear();
		}

		@Override
		public Iterator<V> iterator() {
			return new ValueIterator();
		}

		@Override
		public void forEach(Consumer<? super V> action) {
			final Node<V>[] tab = table;
			if ( action == null ) {
				throw new NullPointerException();
			}
			if ( size > 0 && tab != null ) {
				final int mc = modCount;
				for ( Node<V> e : tab ) {
					for ( ; e != null; e = e.next ) {
						action.accept( e.value );
					}
				}
				if ( modCount != mc ) {
					throw new ConcurrentModificationException();
				}
			}
		}
	}

	// =========================================================================
	// CUSTOM entry set — replaces HashMap.EntrySet
	//
	// Entries expose (EntityKey, V) pairs.  The EntityKey is reconstructed
	// from the Node's key-part fields via Node.toEntityKey().
	// =========================================================================

	/**
	 * A logical entry pairing a reconstructed {@link EntityKey} with its
	 * value, plus the {@code rootEntityName} for callers that need it.
	 */
	public static final class Entry<V> {
		private final Node<V> node;

		Entry(Node<V> node) {
			this.node = node;
		}

		public EntityKey getKey() {
			return node.toEntityKey();
		}

		public String getRootEntityName() {
			return node.rootEntityName;
		}

		public Object getIdentifier() {
			return node.identifier;
		}

		public @Nullable Object getChangesetId() {
			return node.changesetId;
		}

		public V getValue() {
			return node.value;
		}

		public V setValue(V newValue) {
			V old = node.value;
			node.value = newValue;
			return old;
		}
	}

	/**
	 * Returns a set view of the entries in this map.
	 */
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
			EntityKeyMap.this.clear();
		}

		@Override
		public Iterator<Entry<V>> iterator() {
			return new EntryIterator();
		}

		@Override
		public void forEach(Consumer<? super Entry<V>> action) {
			final Node<V>[] tab = table;
			if ( action == null ) {
				throw new NullPointerException();
			}
			if ( size > 0 && tab != null ) {
				final int mc = modCount;
				for ( Node<V> e : tab ) {
					for ( ; e != null; e = e.next ) {
						action.accept( new Entry<>( e ) );
					}
				}
				if ( modCount != mc ) {
					throw new ConcurrentModificationException();
				}
			}
		}
	}

	// =========================================================================
	// CUSTOM forEach — convenience methods for the persistence context
	// =========================================================================

	/**
	 * Iterate all entries, providing the reconstructed {@link EntityKey} and
	 * the value.
	 */
	public void forEach(BiConsumer<EntityKey, V> action) {
		final Node<V>[] tab = table;
		if ( action == null ) {
			throw new NullPointerException();
		}
		if ( size > 0 && tab != null ) {
			final int mc = modCount;
			for ( Node<V> e : tab ) {
				for ( ; e != null; e = e.next ) {
					action.accept( e.toEntityKey(), e.value );
				}
			}
			if ( modCount != mc ) {
				throw new ConcurrentModificationException();
			}
		}
	}

	/**
	 * Iterate all entries, providing the raw key-part fields and the value.
	 * This avoids allocating an {@link EntityKey} wrapper on each call.
	 */
	public void forEachRaw(RawEntryConsumer<V> action) {
		final Node<V>[] tab = table;
		if ( action == null ) {
			throw new NullPointerException();
		}
		if ( size > 0 && tab != null ) {
			final int mc = modCount;
			for ( Node<V> e : tab ) {
				for ( ; e != null; e = e.next ) {
					action.accept( e.rootEntityName, e.identifier, e.changesetId, e.value );
				}
			}
			if ( modCount != mc ) {
				throw new ConcurrentModificationException();
			}
		}
	}

	/**
	 * Callback for {@link #forEachRaw}.
	 */
	@FunctionalInterface
	public interface RawEntryConsumer<V> {
		void accept(String rootEntityName, Object identifier,
				@Nullable Object changesetId, V value);
	}

	// =========================================================================
	// Iterators — adapted from java.util.HashMap.HashIterator
	//
	// DIFFERENCE: iterates Node<V> (our custom Node) instead of
	// HashMap.Node<K,V>.  The logic is otherwise identical.
	// =========================================================================

	abstract class HashIterator {
		Node<V> next;
		Node<V> current;
		int expectedModCount;
		int index;

		HashIterator() {
			expectedModCount = modCount;
			final Node<V>[] t = table;
			current = next = null;
			index = 0;
			if ( t != null && size > 0 ) {
				do {
				}
				while ( index < t.length && ( next = t[index++] ) == null );
			}
		}

		public final boolean hasNext() {
			return next != null;
		}

		final Node<V> nextNode() {
			final Node<V> e = next;
			if ( modCount != expectedModCount ) {
				throw new ConcurrentModificationException();
			}
			if ( e == null ) {
				throw new NoSuchElementException();
			}
			final Node<V>[] t = table;
			if ( ( next = ( current = e ).next ) == null && t != null ) {
				do {
				}
				while ( index < t.length && ( next = t[index++] ) == null );
			}
			return e;
		}

		public final void remove() {
			final Node<V> p = current;
			if ( p == null ) {
				throw new IllegalStateException();
			}
			if ( modCount != expectedModCount ) {
				throw new ConcurrentModificationException();
			}
			current = null;
			removeNode( p.hash, p.rootEntityName, p.identifier, p.changesetId );
			expectedModCount = modCount;
		}
	}

	final class ValueIterator extends HashIterator implements Iterator<V> {
		@Override
		public V next() {
			return nextNode().value;
		}
	}

	final class EntryIterator extends HashIterator implements Iterator<Entry<V>> {
		@Override
		public Entry<V> next() {
			return new Entry<>( nextNode() );
		}
	}

	// =========================================================================
	// toString — for debugging
	// =========================================================================

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder( "{" );
		final Node<V>[] tab = table;
		boolean first = true;
		if ( tab != null && size > 0 ) {
			for ( Node<V> e : tab ) {
				for ( ; e != null; e = e.next ) {
					if ( !first ) {
						sb.append( ", " );
					}
					sb.append( e );
					first = false;
				}
			}
		}
		return sb.append( "}" ).toString();
	}
}
