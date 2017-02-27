/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.cache.infinispan.access;

import org.infinispan.commands.FlagAffectedCommand;
import org.infinispan.context.InvocationContext;
import org.infinispan.interceptors.locking.ClusteringDependentLogic;

public class UnorderedReplicationLogic extends ClusteringDependentLogic.ReplicationLogic {

	@Override
	public Commit commitType(
			FlagAffectedCommand command, InvocationContext ctx, Object key, boolean removed) {
		Commit commit = super.commitType( command, ctx, key, removed );
		return commit == Commit.NO_COMMIT ? Commit.COMMIT_LOCAL : commit;
	}

}
