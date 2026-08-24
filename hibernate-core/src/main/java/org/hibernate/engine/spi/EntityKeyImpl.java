/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.engine.spi;

import java.io.Serial;

import org.hibernate.AssertionFailure;

/**
 * Default, non-temporal {@link EntityKey} implementation.
 * <p>
 * This class is intentionally minimal: it stores <em>only</em> the entity
 * identifier value. Entity-type discrimination is handled externally by
 * {@link EntityKeyMap}, which stores the root entity name in its internal
 * {@code Node}.
 * <p>
 * <b>Note:</b> {@code equals}/{@code hashCode} compare identifiers only.
 * Two keys for different entity types but with the same id will be
 * considered equal by this class. Correct behaviour relies on
 * {@link EntityKeyMap} providing entity-type-aware lookups.
 *
 * @see EntityKey
 * @see EntityKeyMap
 */
public final class EntityKeyImpl implements EntityKey {

	@Serial
	private static final long serialVersionUID = 1L;

	private final Object identifier;

	public EntityKeyImpl(Object id) {
		if ( id == null ) {
			throw new AssertionFailure( "null identifier" );
		}
		this.identifier = id;
	}

	@Override
	public Object getIdentifier() {
		return identifier;
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
				&& java.util.Objects.equals( getChangesetId(), otherKey.getChangesetId() );
	}

	@Override
	public int hashCode() {
		return identifier.hashCode();
	}

	@Override
	public String toString() {
		return "EntityKey(" + identifier + ")";
	}
}
