package org.hibernate.cache.infinispan.access;

import java.util.concurrent.TimeUnit;

import org.hibernate.cache.infinispan.impl.BaseTransactionalDataRegion;
import org.hibernate.cache.infinispan.util.Tombstone;

import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.context.InvocationContext;
import org.infinispan.factories.annotations.Start;
import org.infinispan.interceptors.DDAsyncInterceptor;
import org.infinispan.metadata.EmbeddedMetadata;
import org.infinispan.metadata.Metadata;

public class TombstoneMetadataInterceptor extends DDAsyncInterceptor {

	private final Metadata expiringMetadata;
	private Metadata defaultMetadata;

	public TombstoneMetadataInterceptor(BaseTransactionalDataRegion region) {
		expiringMetadata = new EmbeddedMetadata.Builder()
				.lifespan( region.getTombstoneExpiration(), TimeUnit.MILLISECONDS).build();
	}

	@Start
	public void start() {
		defaultMetadata = new EmbeddedMetadata.Builder()
				.lifespan(cacheConfiguration.expiration().lifespan())
				.maxIdle(cacheConfiguration.expiration().maxIdle()).build();
	}

	@Override
	public Object visitPutKeyValueCommand(
			InvocationContext ctx, PutKeyValueCommand command) throws Throwable {
		overrideMetadata( command );
		return super.visitPutKeyValueCommand( ctx, command );    // TODO: Customise this generated block
	}

	private void overrideMetadata(PutKeyValueCommand command) {
		Object value = command.getValue();
		if (value instanceof Tombstone ) {
			command.setMetadata(expiringMetadata);
		}
		else {
			command.setMetadata(defaultMetadata);
		}
	}

}
