/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.engine.spi;

import java.util.AbstractSet;
import java.util.Iterator;

import org.hibernate.persister.entity.EntityPersister;

/**
 * A set of {@link EntityKey} instances backed by an {@link EntityKeyOpenMap}.
 *
 * @see EntityKeyOpenMap
 */
public class EntityKeyOpenSet extends AbstractSet<EntityKey> {

	private static final Object PRESENT = new Object();

	private final EntityKeyOpenMap<Object> map;

	public EntityKeyOpenSet() {
		this.map = new EntityKeyOpenMap<>();
	}

	public EntityKeyOpenSet(int initialCapacity) {
		this.map = new EntityKeyOpenMap<>( initialCapacity );
	}

	public boolean add(EntityPersister persister, EntityKey key) {
		return map.put( persister, key, PRESENT ) == null;
	}

	public boolean contains(EntityPersister persister, EntityKey key) {
		return map.containsKey( persister, key );
	}

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

	public EntityKeyOpenMap<Object> backingMap() {
		return map;
	}
}
