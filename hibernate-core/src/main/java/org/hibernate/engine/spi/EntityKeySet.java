/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.engine.spi;

import java.util.AbstractSet;
import java.util.Iterator;

import org.hibernate.persister.entity.EntityPersister;

/**
 * A set of {@link EntityKey} instances backed by an {@link EntityKeyMap}.
 * <p>
 * Like {@code EntityKeyMap}, lookups require an {@link EntityPersister}
 * alongside the {@link EntityKey} so that entity-type discrimination
 * can happen inside the map's {@code Node} structure rather than on
 * the key itself.
 */
public class EntityKeySet extends AbstractSet<EntityKey> {

	private static final Object PRESENT = new Object();

	private final EntityKeyMap<Object> map;

	public EntityKeySet() {
		this.map = new EntityKeyMap<>();
	}

	public EntityKeySet(int initialCapacity) {
		this.map = new EntityKeyMap<>( initialCapacity );
	}

	/**
	 * Add the given key.  The key must carry a persister
	 * (e.g. created via {@link EntityKey#of(Object, EntityPersister)}).
	 */
	public boolean add(EntityPersister persister, EntityKey key) {
		return map.put( persister, key, PRESENT ) == null;
	}

	/**
	 * Check whether the set contains the given key.
	 */
	public boolean contains(EntityPersister persister, EntityKey key) {
		return map.containsKey( persister, key );
	}

	/**
	 * Remove the given key.
	 *
	 * @return {@code true} if the key was present
	 */
	public boolean remove(EntityPersister persister, EntityKey key) {
		return map.remove( persister, key ) != null;
	}

	@Override
	public int size() {
		return map.size();
	}

	@Override
	public boolean isEmpty() {
		return map.isEmpty();
	}

	@Override
	public void clear() {
		map.clear();
	}

	/**
	 * Iterate over all keys. The returned {@link EntityKey} instances
	 * are reconstructed from the map's internal nodes.
	 */
	@Override
	public Iterator<EntityKey> iterator() {
		final var entryIter = map.entrySet().iterator();
		return new Iterator<>() {
			@Override
			public boolean hasNext() {
				return entryIter.hasNext();
			}

			@Override
			public EntityKey next() {
				return entryIter.next().getKey();
			}

			@Override
			public void remove() {
				entryIter.remove();
			}
		};
	}

	/**
	 * Provides direct access to the backing map for serialization etc.
	 */
	public EntityKeyMap<Object> backingMap() {
		return map;
	}
}
