/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.sql.results.graph.entity.internal;

import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Consumer;

import org.hibernate.engine.spi.EntityKey;
import org.hibernate.metamodel.mapping.AttributeMapping;
import org.hibernate.metamodel.mapping.internal.ToOneAttributeMapping;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.property.access.spi.Setter;
import org.hibernate.spi.NavigablePath;
import org.hibernate.sql.results.graph.AssemblerCreationState;
import org.hibernate.sql.results.graph.DomainResult;
import org.hibernate.sql.results.graph.InitializerData;
import org.hibernate.sql.results.graph.InitializerParent;
import org.hibernate.sql.results.jdbc.spi.RowProcessingState;
import org.hibernate.type.Type;

import static org.hibernate.internal.log.LoggingHelper.toLoggableString;

public class BatchEntitySelectFetchInitializer extends AbstractBatchEntitySelectFetchInitializer<BatchEntitySelectFetchInitializer.BatchEntitySelectFetchInitializerData> {
	protected final AttributeMapping[] parentAttributes;
	protected final Setter referencedModelPartSetter;
	protected final Type referencedModelPartType;

	public static class BatchEntitySelectFetchInitializerData extends AbstractBatchEntitySelectFetchInitializerData {
		private HashMap<EntityKey, ParentInfoList> toBatchLoad;

		public BatchEntitySelectFetchInitializerData(
				BatchEntitySelectFetchInitializer initializer,
				RowProcessingState rowProcessingState) {
			super( initializer, rowProcessingState );
		}
	}

	public BatchEntitySelectFetchInitializer(
			InitializerParent<?> parentAccess,
			ToOneAttributeMapping referencedModelPart,
			NavigablePath fetchedNavigable,
			EntityPersister concreteDescriptor,
			DomainResult<?> keyResult,
			boolean affectedByFilter,
			AssemblerCreationState creationState) {
		super( parentAccess, referencedModelPart, fetchedNavigable, concreteDescriptor, keyResult, affectedByFilter, creationState );
		parentAttributes = getParentEntityAttributes( referencedModelPart.getAttributeName() );
		referencedModelPartSetter = referencedModelPart.getPropertyAccess().getSetter();
		referencedModelPartType =
				referencedModelPart.findContainingEntityMapping().getEntityPersister()
						.getPropertyType( referencedModelPart.getAttributeName() );
	}

	@Override
	protected InitializerData createInitializerData(RowProcessingState rowProcessingState) {
		return new BatchEntitySelectFetchInitializerData( this, rowProcessingState );
	}

	@Override
	protected void registerResolutionListener(BatchEntitySelectFetchInitializerData data) {
		final var rowProcessingState = data.getRowProcessingState();
		final var owningData = owningEntityInitializer.getData( rowProcessingState );
		var toBatchLoad = data.toBatchLoad;
		if ( toBatchLoad == null ) {
			toBatchLoad = data.toBatchLoad = new HashMap<>();
		}
		// Always register the entity key for resolution
		final var parentInfos = toBatchLoad.computeIfAbsent( data.entityKey, key -> new ParentInfoList() );
		// But only add the parent info if the parent entity is not already initialized
		if ( owningData.getState() != State.INITIALIZED ) {
			final var parentAttribute =
					parentAttributes[owningEntityInitializer.getConcreteDescriptor( owningData )
							.getSubclassId()];
			if ( parentAttribute != null ) {
				parentInfos.add( new ParentInfo(
						owningEntityInitializer.getTargetInstance( owningData ),
						parentAttribute.getStateArrayPosition()
				) );
			}
		}
	}

	private static value class ParentInfo {
		private final Object parentInstance;
		private final short propertyIndex;

		public ParentInfo(Object parentInstance, int propertyIndex) {
			this.parentInstance = parentInstance;
			this.propertyIndex = (short) propertyIndex;
		}
	}

	private static class ParentInfoList implements Iterable<ParentInfo> {
		private static final int DEFAULT_CAPACITY = 10;

		private static final ParentInfo[] DEFAULTCAPACITY_EMPTY_ELEMENTDATA = {};

		private ParentInfo[] elementData;

		private int size;

		private int modCount = 0;

		ParentInfoList() {
			this.elementData = DEFAULTCAPACITY_EMPTY_ELEMENTDATA;
		}

		void add(ParentInfo e) {
			add(e, elementData, size);
		}

		void add(ParentInfo e, ParentInfo[] elementData, int s) {
			if (s == elementData.length)
				elementData = grow();
			elementData[s] = e;
			size = s + 1;
		}

		public Iterator<ParentInfo> iterator() {
			return new Itr();
		}

		private ParentInfo[] grow() {
			return grow(size + 1);
		}

		private ParentInfo[] grow(int minCapacity) {
			int oldCapacity = elementData.length;
			if (oldCapacity > 0 || elementData != DEFAULTCAPACITY_EMPTY_ELEMENTDATA) {
				int newCapacity = newLength(oldCapacity,
						minCapacity - oldCapacity, /* minimum growth */
						oldCapacity >> 1           /* preferred growth */);
				return elementData = Arrays.copyOf(elementData, newCapacity);
			} else {
				return elementData = new ParentInfo[Math.max(DEFAULT_CAPACITY, minCapacity)];
			}
		}

		public static final int SOFT_MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8;

		public static int newLength(int oldLength, int minGrowth, int prefGrowth) {
			// preconditions not checked because of inlining
			// assert oldLength >= 0
			// assert minGrowth > 0

			int prefLength = oldLength + Math.max(minGrowth, prefGrowth); // might overflow
			if (0 < prefLength && prefLength <= SOFT_MAX_ARRAY_LENGTH) {
				return prefLength;
			} else {
				// put code cold in a separate method
				return hugeLength(oldLength, minGrowth);
			}
		}

		private static int hugeLength(int oldLength, int minGrowth) {
			int minLength = oldLength + minGrowth;
			if (minLength < 0) { // overflow
				throw new OutOfMemoryError(
						"Required array length " + oldLength + " + " + minGrowth + " is too large");
			} else if (minLength <= SOFT_MAX_ARRAY_LENGTH) {
				return SOFT_MAX_ARRAY_LENGTH;
			} else {
				return minLength;
			}
		}

		private class Itr implements Iterator<ParentInfo> {
			int cursor;       // index of next element to return
			int lastRet = -1; // index of last element returned; -1 if no such
			int expectedModCount = modCount;

			// prevent creating a synthetic constructor
			Itr() {}

			public boolean hasNext() {
				return cursor != size;
			}

			public ParentInfo next() {
				checkForComodification();
				int i = cursor;
				if (i >= size)
					throw new NoSuchElementException();
				ParentInfo[] elementData = ParentInfoList.this.elementData;
				if (i >= elementData.length)
					throw new ConcurrentModificationException();
				cursor = i + 1;
				return elementData[lastRet = i];
			}

			public void remove() {
				throw new IllegalStateException("NYE");
			}

			@Override
			public void forEachRemaining(Consumer<? super ParentInfo> action) {
				Objects.requireNonNull(action);
				final int size = ParentInfoList.this.size;
				int i = cursor;
				if (i < size) {
					final ParentInfo[] es = elementData;
					if (i >= es.length)
						throw new ConcurrentModificationException();
					for (; i < size && modCount == expectedModCount; i++)
						action.accept(elementData[i]);
					// update once at end to reduce heap write traffic
					cursor = i;
					lastRet = i - 1;
					checkForComodification();
				}
			}

			final void checkForComodification() {
				if (modCount != expectedModCount)
					throw new ConcurrentModificationException();
			}
		}
	}

	@Override
	public void endLoading(BatchEntitySelectFetchInitializerData data) {
		super.endLoading( data );
		final var toBatchLoad = data.toBatchLoad;
		if ( toBatchLoad != null ) {
			final var session = data.getRowProcessingState().getSession();
			final var factory = session.getFactory();
			final var persistenceContext = session.getPersistenceContextInternal();
			for ( var entry : toBatchLoad.entrySet() ) {
				final var entityKey = entry.getKey();
				final var parentInfos = entry.getValue();
				final Object instance = loadInstance( entityKey, toOneMapping, affectedByFilter, session );
				for ( var parentInfo : parentInfos ) {
					final Object parentInstance = parentInfo.parentInstance;
					final var entityEntry = persistenceContext.getEntry( parentInstance );
					referencedModelPartSetter.set( parentInstance, instance );
					final var loadedState = entityEntry.getLoadedState();
					if ( loadedState != null ) {
						loadedState[parentInfo.propertyIndex] =
								referencedModelPartType.deepCopy( instance, factory );
					}
				}
			}
			data.toBatchLoad = null;
		}
	}

	@Override
	public String toString() {
		return "BatchEntitySelectFetchInitializer("
				+ toLoggableString( getNavigablePath() ) + ")";
	}

}
