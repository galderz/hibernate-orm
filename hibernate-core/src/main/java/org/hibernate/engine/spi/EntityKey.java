/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.engine.spi;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import org.hibernate.AssertionFailure;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.type.Type;

import org.checkerframework.checker.nullness.qual.Nullable;

import static org.hibernate.pretty.MessageHelper.infoString;

public interface EntityKey extends Serializable {

	public boolean isBatchLoadable(LoadQueryInfluencers influencers);

	public Object getIdentifierValue();

	public Object getIdentifier();

	public String getEntityName();

	public EntityPersister getPersister();

	/**
	 * The audit changeset identifier for this key, or {@code null} for
	 * non-temporal entities.
	 * When non-null, this entity is a read-only historical snapshot.
	 */
	public @Nullable Object getChangesetId();

	/**
	 * Whether this key refers to a temporal (historical) snapshot.
	 */
	public boolean isTemporal();

	/**
	 * Custom deserialization routine used during deserialization of a
	 * Session/PersistenceContext for increased performance.
	 *
	 * @param ois The stream from which to read the entry.
	 * @param sessionFactory The SessionFactory owning the Session being deserialized.
	 *
	 * @return The deserialized EntityKey
	 *
	 * @throws IOException Thrown by Java I/O
	 * @throws ClassNotFoundException Thrown by Java I/O
	 */
	public static EntityKey deserialize(ObjectInputStream ois, SessionFactoryImplementor sessionFactory) throws IOException, ClassNotFoundException {
		final Object id = ois.readObject();
		final String entityName = (String) ois.readObject();
		final Object changesetId = ois.readObject();
		final EntityPersister entityPersister =
				sessionFactory.getMappingMetamodel()
						.getEntityDescriptor( entityName );
		return changesetId != null
				? new TemporalEntityKey( id, entityPersister, changesetId )
				: new EntityKeyVC( id, entityPersister );
	}
}
