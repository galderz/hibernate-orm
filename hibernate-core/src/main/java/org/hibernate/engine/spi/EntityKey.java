/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.engine.spi;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;

import org.hibernate.persister.entity.EntityPersister;

import org.checkerframework.checker.nullness.qual.Nullable;

import static org.hibernate.pretty.MessageHelper.infoString;


/**
 * Uniquely identifies an entity instance in a particular Session by identifier.
 * Note that it's only safe to be used within the scope of a Session: it doesn't
 * consider for example the tenantId as part of the equality definition.
 * <p>
 * An {@code EntityKey} carries <em>only</em> the entity identifier value.
 * Entity-type discrimination (root entity name) and temporal changeset
 * information are handled externally by {@link EntityKeyMap}, which stores
 * this metadata in its internal {@code Node} structure.
 * <p>
 *
 * Performance considerations: lots of instances of this type are created at
 * runtime. The canonical implementation ({@link EntityKeyImpl}) stores just
 * a single {@code Object identifier} field.
 *
 * @author Gavin King
 * @author Sanne Grinovero
 *
 * @see EntityKeyMap
 */
public interface EntityKey extends Serializable {

	/**
	 * The entity identifier value.
	 */
	Object getIdentifier();

	/**
	 * Synonym for {@link #getIdentifier()}.
	 */
	default Object getIdentifierValue() {
		return getIdentifier();
	}

	/**
	 * The audit changeset identifier for this key, or {@code null} for
	 * non-temporal entities.
	 */
	default @Nullable Object getChangesetId() {
		return null;
	}

	/**
	 * Whether this key refers to a temporal (historical) snapshot.
	 */
	default boolean isTemporal() {
		return false;
	}



	// -- factory methods ------------------------------------------------------

	/**
	 * Create an {@code EntityKey} for the given identifier (without persister).
	 */
	static EntityKey of(Object id) {
		return new EntityKeyImpl( id );
	}

	/**
	 * The entity persister, if available. {@link EntityKeyImpl} does not carry
	 * a persister and will throw. Keys obtained from {@link EntityKeyMap}
	 * iteration or from {@link EntityHolder#getEntityKey()} do carry a persister.
	 * <p>
	 * Prefer passing the persister explicitly via the
	 * {@link PersistenceContext} method that accepts
	 * {@code (EntityPersister, EntityKey)}.
	 */
	default EntityPersister getPersister() {
		throw new UnsupportedOperationException(
				"This EntityKey does not carry a persister reference. "
						+ "Use the persister from the EntityHolder or session context." );
	}

	/**
	 * The entity name, if available. Delegates to {@link #getPersister()}.
	 */
	default String getEntityName() {
		return getPersister().getEntityName();
	}

	/**
	 * Whether this entity is eligible for batch loading.
	 */
	default boolean isBatchLoadable(LoadQueryInfluencers influencers) {
		return influencers.effectivelyBatchLoadable( getPersister() );
	}

	// -- factory methods ------------------------------------------------------

	/**
	 * Create an {@code EntityKey} with a persister reference.
	 */
	static EntityKey of(Object id, EntityPersister persister) {
		return new EntityKeyWithPersister( id, persister );
	}

	/**
	 * Create a temporal {@code EntityKey} for the given identifier and
	 * changeset id.
	 */
	static EntityKey of(Object id, Object changesetId) {
		return new TemporalEntityKey( id, changesetId );
	}

	/**
	 * Create a temporal {@code EntityKey} with a persister reference.
	 */
	static EntityKey of(Object id, EntityPersister persister, Object changesetId) {
		return new TemporalEntityKeyWithPersister( id, persister, changesetId );
	}

	// -- serialization --------------------------------------------------------

	/**
	 * Custom serialization. Only works on keys that carry a persister.
	 */
	default void serialize(java.io.ObjectOutputStream oos) throws IOException {
		oos.writeObject( getIdentifier() );
		oos.writeObject( getEntityName() );
		oos.writeObject( getChangesetId() );
	}

	/**
	 * Custom deserialization routine used during deserialization of a
	 * Session/PersistenceContext for increased performance.
	 *
	 * @param ois            The stream from which to read the entry.
	 * @param sessionFactory The SessionFactory owning the Session being deserialized.
	 * @return The deserialized EntityKey
	 * @throws IOException            Thrown by Java I/O
	 * @throws ClassNotFoundException Thrown by Java I/O
	 */
	static EntityKey deserialize(ObjectInputStream ois, SessionFactoryImplementor sessionFactory)
			throws IOException, ClassNotFoundException {
		final Object id = ois.readObject();
		final String entityName = (String) ois.readObject();
		final Object changesetId = ois.readObject();
		final EntityPersister entityPersister =
				sessionFactory.getMappingMetamodel()
						.getEntityDescriptor( entityName );
		return changesetId != null
				? EntityKey.of( id, entityPersister, changesetId )
				: EntityKey.of( id, entityPersister );
	}

	// -- toString helper ------------------------------------------------------

	/**
	 * Produce a human-readable representation using the given persister.
	 */
	default String toString(EntityPersister persister) {
		return "EntityKey" + infoString( persister, getIdentifier(), persister.getFactory() );
	}
}
