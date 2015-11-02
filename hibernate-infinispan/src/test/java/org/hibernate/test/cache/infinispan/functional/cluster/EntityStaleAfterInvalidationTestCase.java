package org.hibernate.test.cache.infinispan.functional.cluster;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.stat.SecondLevelCacheStatistics;
import org.hibernate.test.cache.infinispan.functional.Item;
import org.infinispan.Cache;
import org.infinispan.commands.tx.CommitCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.interceptors.InvalidationInterceptor;
import org.infinispan.interceptors.base.CommandInterceptor;
import org.infinispan.manager.CacheContainer;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import org.junit.Test;

import javax.transaction.TransactionManager;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import static org.infinispan.test.TestingUtil.withTx;
import static org.junit.Assert.assertEquals;

public class EntityStaleAfterInvalidationTestCase extends DualNodeTestCase {
   private static final Log log = LogFactory.getLog(EntityStaleAfterInvalidationTestCase.class);

   @Override
   public String[] getMappings() {
      return new String[] {
         "cache/infinispan/functional/Item.hbm.xml",
      };
   }

   @Override
   protected boolean rebuildSessionFactoryOnError() {
      return false;
   }

   @Test
   public void testAvoidStaleReadAfterInvalidation() throws Exception {
      final TransactionManager localTM = DualNodeJtaTransactionManagerImpl.getInstance( DualNodeTestCase.LOCAL );
      final SessionFactoryImplementor localFactory = sessionFactory();
      TransactionManager remoteTM = DualNodeJtaTransactionManagerImpl.getInstance( DualNodeTestCase.REMOTE );
      SessionFactoryImplementor remoteFactory = secondNodeEnvironment().getSessionFactory();

      SecondLevelCacheStatistics remote2lcStats = remoteFactory.getStatistics()
         .getSecondLevelCacheStatistics(Item.class.getName());

      assertEquals(0, remote2lcStats.getPutCount());

      final Item item = new Item( "item-A", "Item A" );
      final Long id = insertItem(item, localTM, localFactory);
      assertEquals("item-A", queryEntity(id, localTM, localFactory));
      assertEquals("item-A", queryEntity(id, remoteTM, remoteFactory));
      assertEquals(1, remote2lcStats.getPutCount());

      CacheContainer localManager = ClusterAwareRegionFactory.getCacheManager( DualNodeTestCase.LOCAL );
      Cache localItemCache = localManager.getCache( Item.class.getName() );
      CountDownLatch beforeStaleRead = new CountDownLatch( 1 );
      CountDownLatch afterStateRead = new CountDownLatch( 1 );
      localItemCache.getAdvancedCache().addInterceptorBefore(
         new SlowAfterInvalidationInterceptor(beforeStaleRead, afterStateRead), InvalidationInterceptor.class);

      Thread updateThread = new Thread() {
         @Override
         public void run() {
            try {
               updateItem(id, localTM, localFactory);
            } catch (Exception e) {
               e.printStackTrace();  // TODO: Customise this generated block
            }
         }
      };

      updateThread.start();
      beforeStaleRead.await();
      try {
         String itemName = queryEntity(id, remoteTM, remoteFactory);
         // Queried entity must come from the database and not the cache
         // so that it can be guaranteed that the invalidation went through
         assertEquals(2, remote2lcStats.getPutCount());
         assertEquals("item-B", itemName);
      } finally {
         afterStateRead.countDown();
      }
   }

   private Long insertItem(final Item item, TransactionManager tm, final SessionFactory sf) throws Exception {
      log.info( "Insert item" );
      return withTx(tm, new Callable<Long>() {
         @Override
         public Long call() throws Exception {
            Session s = sf.openSession();
            s.getTransaction().begin();
            s.persist(item);
            s.getTransaction().commit();
            s.close();
            return item.getId();
         }
      });
   }

   private String queryEntity(final Long id, TransactionManager tm, final SessionFactory sf) throws Exception {
      log.info( "Query item" );
      return withTx(tm, new Callable<String>() {
         @Override
         public String call() throws Exception {
            Session s = sf.openSession();
            Item item = (Item) s.load( Item.class, id );
            String name = item.getName();
            s.close();
            return name;
         }
      });
   }

   private String updateItem(final Long id, TransactionManager tm, final SessionFactory sf) throws Exception {
      log.info( "Update item" );
      return withTx(tm, new Callable<String>() {
         @Override
         public String call() throws Exception {
            Session s = sf.openSession();
            s.getTransaction().begin();
            Item item = (Item) s.load( Item.class, id );
            item.setName("item-B");
            item.setDescription("Item B");
            s.update(item);
            String name = item.getName();
            s.getTransaction().commit();
            s.close();
            return name;
         }
      });
   }

   private static class SlowAfterInvalidationInterceptor extends CommandInterceptor {
      private static final Log log = LogFactory.getLog(SlowAfterInvalidationInterceptor.class);
      private final CountDownLatch beforeStaleRead;
      private final CountDownLatch afterStateRead;

      public SlowAfterInvalidationInterceptor(CountDownLatch beforeStaleRead, CountDownLatch afterStateRead) {
         this.beforeStaleRead = beforeStaleRead;
         this.afterStateRead = afterStateRead;
      }

//      @Override
//      public Object visitPrepareCommand(TxInvocationContext ctx, PrepareCommand command) throws Throwable {
//         log.debug("On visitPrepareCommand, before");
//         Object o = super.visitPrepareCommand(ctx, command);
//         log.debug("On visitPrepareCommand, after");
//         beforeStaleRead.countDown(); // Potentially read after stale can be done now
//         afterStateRead.await(); // Wait until state read has happened
//         return o;
//      }

      @Override
      public Object visitCommitCommand(TxInvocationContext ctx, CommitCommand command) throws Throwable {
         log.debug("On visitCommitCommand, before");
         Object o = super.visitCommitCommand(ctx, command);
         log.debug("On visitCommitCommand, after");
         beforeStaleRead.countDown(); // Potentially read after stale can be done now
         afterStateRead.await(); // Wait until state read has happened
         return o;
      }

//      @Override
//      public Object visitPrepareCommand(TxInvocationContext ctx, PrepareCommand command) throws Throwable {
//         log.debug("On visitPrepareCommand, before");
//         Object o = super.visitPrepareCommand(ctx, command);
//         log.debug("On visitPrepareCommand, after");
//         beforeStaleRead.countDown(); // Potentially read after stale can be done now
//         afterStateRead.await(); // Wait until state read has happened
//         return o;
//      }
   }

}
