/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.engine.spi;

import java.io.Serial;
import java.util.Objects;

import org.hibernate.AssertionFailure;
import org.hibernate.persister.entity.EntityPersister;

import static org.hibernate.pretty.MessageHelper.infoString;

/**
 * A temporal {@link EntityKey} that additionally carries an
 * {@link EntityPersister} reference.
 *
 * @see EntityKey#of(Object, EntityPersister, Object)
 * @see TemporalEntityKey
 */
final class TemporalEntityKeyWithPersister implements EntityKey {

	@Serial
	private static final long serialVersionUID = 1L;

	private final Object identifier;
	private final EntityPersister persister;
	private final Object changesetId;

	TemporalEntityKeyWithPersister(Object id, EntityPersister persister, Object changesetId) {
		if ( id == null ) {
			throw new AssertionFailure( "null identifier (" + persister.getEntityName() + ")" );
		}
		if ( changesetId == null ) {
			throw new AssertionFailure( "null changesetId" );
		}
		this.identifier = id;
		this.persister = persister;
		this.changesetId = changesetId;
	}

	@Override
	public Object getIdentifier() {
		return identifier;
	}

	@Override
	public EntityPersister getPersister() {
		return persister;
	}

	@Override
	public Object getChangesetId() {
		return changesetId;
	}

	@Override
	public boolean isTemporal() {
		return true;
	}

	@Override
	public boolean equals(@org.checkerframework.checker.nullness.qual.Nullable Object other) {
		if ( this == other ) {
			return true;
		}
		if ( !( other instanceof EntityKey otherKey ) ) {
			return false;
		}
		return identifier.equals( otherKey.getIdentifier() )
				&& Objects.equals( changesetId, otherKey.getChangesetId() );
	}

	@Override
	public int hashCode() {
		return 37 * identifier.hashCode() + changesetId.hashCode();
	}

	@Override
	public String toString() {
		return "EntityKey" + infoString( persister, identifier, persister.getFactory() ) + "@" + changesetId;
	}
}
