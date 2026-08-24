/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.stat.internal;

import java.util.Set;

import org.hibernate.engine.spi.PersistenceContext;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.stat.SessionStatistics;

import static java.util.Collections.unmodifiableSet;

/**
 * @author Gavin King
 */
public class SessionStatisticsImpl implements SessionStatistics {

	private final PersistenceContext persistenceContext;

	public SessionStatisticsImpl(SessionImplementor session) {
		persistenceContext = session.getPersistenceContextInternal();
	}

	public int getEntityCount() {
		return persistenceContext.getNumberOfManagedEntities();
	}

	public int getCollectionCount() {
		return persistenceContext.getCollectionEntriesSize();
	}

	public Set<?> getEntityKeys() {
		final var map = persistenceContext.getEntitiesByKey();
		final java.util.Set<org.hibernate.engine.spi.EntityKey> keys = new java.util.HashSet<>( map.size() );
		for ( var entry : map.entrySet() ) {
			keys.add( entry.getKey() );
		}
		return unmodifiableSet( keys );
	}

	public Set<?> getCollectionKeys() {
		return unmodifiableSet( persistenceContext.getCollectionsByKey().keySet() );
	}

	public String toString() {
		return "SessionStatistics["
			+ "entity count=" + getEntityCount()
			+ ",collection count=" + getCollectionCount()
			+ ']';
	}

}
