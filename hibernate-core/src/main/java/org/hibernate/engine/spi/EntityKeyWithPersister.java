/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.engine.spi;

import java.io.Serial;

import org.hibernate.AssertionFailure;
import org.hibernate.persister.entity.EntityPersister;

import static org.hibernate.pretty.MessageHelper.infoString;

/**
 * An {@link EntityKey} that additionally carries an {@link EntityPersister}
 * reference for convenience access to entity name, batch-loadability, and
 * serialization.
 * <p>
 * Instances are typically created by
 * {@link SharedSessionContractImplementor#generateEntityKey} and used as
 * short-lived lookup keys that pass through the persistence context API.
 * The internal storage ({@link EntityKeyMap}) does <em>not</em> rely on
 * the persister being present on the key — it stores the root entity
 * name in its own {@code Node}.
 *
 * @see EntityKey#of(Object, EntityPersister)
 */
final class EntityKeyWithPersister implements EntityKey {

	@Serial
	private static final long serialVersionUID = 1L;

	private final Object identifier;
	private final EntityPersister persister;

	EntityKeyWithPersister(Object id, EntityPersister persister) {
		if ( id == null ) {
			throw new AssertionFailure( "null identifier (" + persister.getEntityName() + ")" );
		}
		this.identifier = id;
		this.persister = persister;
	}

	@Override
	public Object getIdentifier() {
		return identifier;
	}

	@Override
	public EntityPersister getPersister() {
		return persister;
	}

	// --- equals/hashCode: identifier-only (same contract as EntityKeyImpl) ---

	@Override
	public boolean equals(Object other) {
		if ( this == other ) {
			return true;
		}
		if ( !( other instanceof EntityKey otherKey ) ) {
			return false;
		}
		return identifier.equals( otherKey.getIdentifier() )
				&& java.util.Objects.equals( getChangesetId(), otherKey.getChangesetId() );
	}

	@Override
	public int hashCode() {
		return identifier.hashCode();
	}

	@Override
	public String toString() {
		return "EntityKey" + infoString( persister, identifier, persister.getFactory() );
	}
}
