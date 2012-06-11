package org.hibernate.test.cache.infinispan.functional.cluster;

import org.hibernate.Criteria;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.cache.spi.CacheKey;
import org.hibernate.cache.spi.NaturalIdCacheKey;
import org.hibernate.criterion.Restrictions;
import org.hibernate.stat.Statistics;
import org.hibernate.test.cache.infinispan.functional.NonBasicCitizen;
import org.hibernate.test.cache.infinispan.functional.CitizenPK;
import org.hibernate.test.cache.infinispan.functional.NonBasicIdOnManyToOne;
import org.hibernate.test.cache.infinispan.functional.State;
import org.infinispan.Cache;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.manager.CacheContainer;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryVisited;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryInvalidated;
import org.infinispan.notifications.cachelistener.event.CacheEntryVisitedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryInvalidatedEvent;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import org.jboss.util.collection.ConcurrentSet;
import org.junit.After;
import org.junit.Test;

import javax.transaction.TransactionManager;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.infinispan.test.TestingUtil.tmpDirectory;
import static org.infinispan.test.TestingUtil.withTx;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * // TODO: Document this
 *
 * @author Galder Zamarreño
 * @since // TODO
 */
public class NonBasicIdInvalidationTestCase extends DualNodeTestCase {

   private static final Log log = LogFactory.getLog(NonBasicIdInvalidationTestCase.class);

   private static final long SLEEP_TIME = 50l;
   private static final CitizenPK CUSTOMER_ID = new CitizenPK(1);
   private static int test = 0;

   private static AtomicBoolean touched = new AtomicBoolean(false);

   private static CitizenPK cPK1 = new CitizenPK(87);
   private static CitizenPK cPK2 = new CitizenPK(88);

   @Override
   protected Class<?>[] getAnnotatedClasses() {
      return new Class[]{
            NonBasicCitizen.class, State.class,
            NonBasicIdOnManyToOne.class
      };
   }

   @Test
   public void testAll() throws Exception {
      log.info("*** testAll()");

      // Bind a listener to the "local" cache
      // Our region factory makes its CacheManager available to us
      EmbeddedCacheManager localManager = ClusterAwareRegionFactory.getCacheManager(DualNodeTestCase.LOCAL);
      Cache localCache = localManager.getCache(NonBasicCitizen.class.getName());
      MyListener localListener = new MyListener("local");
      localCache.addListener(localListener);
      TransactionManager localTM = DualNodeJtaTransactionManagerImpl.getInstance(DualNodeTestCase.LOCAL);

      // Bind a listener to the "remote" cache
      CacheContainer remoteManager = ClusterAwareRegionFactory.getCacheManager(DualNodeTestCase.REMOTE);
      Cache remoteCache = remoteManager.getCache(NonBasicCitizen.class.getName());
      MyListener remoteListener = new MyListener("remote");
      remoteCache.addListener(remoteListener);
      TransactionManager remoteTM = DualNodeJtaTransactionManagerImpl.getInstance(DualNodeTestCase.REMOTE);

      SessionFactory localFactory = sessionFactory();
      SessionFactory remoteFactory = secondNodeEnvironment().getSessionFactory();

      try {
         final Statistics stats = localFactory.getStatistics();
         final Statistics remoteStats = remoteFactory.getStatistics();
         stats.setStatisticsEnabled(true);
         remoteStats.setStatisticsEnabled(true);
         assertTrue(remoteListener.isEmpty());
         assertTrue(localListener.isEmpty());

         saveSomeCitizens(localTM, localFactory);
         stats.logSummary();
         log.debug(localListener.toString());
//         remoteStats.logSummary();

         assertTrue(remoteListener.isEmpty());
         assertTrue(localListener.isEmpty());

         // Sleep a bit to let async commit propagate. Really just to
         // help keep the logs organized for debugging any issues
         sleep(SLEEP_TIME);

         log.debug("Find node 0");
         // This actually brings the collection into the cache

         getCitizenWithCriteria(localTM, localFactory);
         stats.logSummary();
//         remoteStats.logSummary();

         sleep(SLEEP_TIME);
         // Now the collection is in the cache so, the 2nd "get"
         // should read everything from the cache
         log.debug("Find(2) node 0");
         localListener.clear();

         getCitizenWithCriteria(localTM, localFactory);
         stats.logSummary();
//         remoteStats.logSummary();

         sleep(SLEEP_TIME);
         // Check the read came from the cache
         log.debug("Check cache 0");
         assertLoadedFromCache(localListener, cPK1);
         stats.logSummary();
         remoteStats.logSummary();

         log.debug("Find node 1");
         // This actually brings the collection into the cache since invalidation is in use
         getCitizenWithCriteria(remoteTM, remoteFactory);

         // Now the collection is in the cache so, the 2nd "get"
         // should read everything from the cache
         log.debug("Find(2) node 1");
         remoteListener.clear();
         getCitizenWithCriteria(remoteTM, remoteFactory);

         // Check the read came from the cache
         log.debug("Check cache 1");
         assertLoadedFromCache(remoteListener, cPK1);

         log.debug("Local keys before modifications are: " + localCache.keySet());

         // Modify customer in remote
         remoteListener.clear();
         //deleteCitizenWithCriteria(remoteTM, remoteFactory);
         log.debug("Modify in cache 1");
         changeCitizen(remoteTM, remoteFactory);
         sleep(250);

         Set localKeys = localCache.keySet();
         Set remoteKeys = remoteCache.keySet();

         String res = "";
         for (String it : localManager.getCacheNames())
            res += "\n" + it;

         res += "\n\n" + "Entries in the cache!";

         for (Object it : localKeys)
            res += "\n" + it.toString();

         res += "\n\n" + "Entries in the remote cache!";

         for (Object it : remoteKeys)
            res += "\n" + it.toString();

         assertEquals("\n not invalidated!" + res, 1, localKeys.size());


         // Only key left is the one for the citizen *not* in France
         assertTrue(localKeys.toString().contains(cPK2.toString()));
         assertTrue(!(localKeys.toString().contains(cPK1.toString())));
      } catch (Exception e) {
         log.error("Error", e);
         throw e;
      } finally {
         withTx(localTM, new Callable<Void>() {
            @Override
            public Void call() throws Exception {
               Session s = sessionFactory().openSession();
               s.beginTransaction();
               s.createQuery("delete NonBasicIdOnManyToOne").executeUpdate();
               s.createQuery("delete NonBasicCitizen").executeUpdate();
               s.createQuery("delete State").executeUpdate();
               s.getTransaction().commit();
               s.close();
               return null;
            }
         });
      }
   }

   private void assertLoadedFromCache(MyListener localListener, CitizenPK id) {
      String res = "";
      if (!(touched.get())) {
         fail("!!!Nothing was visited!");
      }
      for (String vis : localListener.visited) {
         res += "\n !!!" + vis;
         if (vis.contains(id.toString()))
            return;
      }
      fail("Citizen (" + id + ") should have present in the cache" + res);
   }

   private void saveSomeCitizens(TransactionManager tm, final SessionFactory sf) throws Exception {
      final NonBasicCitizen c1 = new NonBasicCitizen();
      final CitizenPK c1PK = cPK1;
      c1.setId(c1PK);
      c1.setFirstname("Emmanuel");
      c1.setLastname("Bernard");
      c1.setSsn("1234");

      final State france = new State();
      france.setName("Ile de France");
      c1.setState(france);

      final NonBasicCitizen c2 = new NonBasicCitizen();
      final CitizenPK c2PK = cPK2;
      c2.setId(c2PK);
      c2.setFirstname("Gavin");
      c2.setLastname("King");
      c2.setSsn("000");
      final State australia = new State();
      australia.setName("Australia");
      c2.setState(australia);

      withTx(tm, new Callable<Void>() {
         @Override
         public Void call() throws Exception {
            Session s = sf.openSession();
            Transaction tx = s.beginTransaction();
            s.persist(australia);
            s.persist(france);
            s.persist(c1);
            s.persist(c2);
            tx.commit();
            s.close();
            return null;
         }
      });
   }

   private void getCitizenWithCriteria(TransactionManager tm, final SessionFactory sf) throws Exception {
      withTx(tm, new Callable<Void>() {
         @Override
         public Void call() throws Exception {
            Session s = sf.openSession();
            Transaction tx = s.beginTransaction();
            CitizenPK cPK = new CitizenPK(87);
            Object on = s.get(NonBasicCitizen.class, cPK1);

            if (on.equals(null))
               assertTrue("Was not able to get the Citizen!", false);
            // cleanup
            tx.commit();
            s.close();
            return null;
         }
      });
   }

   private void changeCitizen(TransactionManager tm, final SessionFactory sf) throws Exception {
      withTx(tm, new Callable<Void>() {
         @Override
         public Void call() throws Exception {
            Session s = sf.openSession();
            Transaction tx = s.beginTransaction();
            /*State france = getState(s, "Ile de France");
            Criteria criteria = s.createCriteria( Citizen.class );
            //criteria.add( Restrictions.naturalId().set( "ssn", "1234" ).set( "state", france ) );
			//criteria.add( Restrictions.eq( "ssn", "1234"));
			//criteria.add( Restrictions.eq( "state", france));
            criteria.setCacheable( true );
            criteria.list();*/
            NonBasicCitizen cit = (NonBasicCitizen) s.get(NonBasicCitizen.class, cPK1);
            cit.setSsn("2345");
            s.update(cit);
            // cleanup
            tx.commit();
            s.close();
            return null;
         }
      });
   }

   private void deleteCitizenWithCriteria(TransactionManager tm, final SessionFactory sf) throws Exception {
      withTx(tm, new Callable<Void>() {
         @Override
         public Void call() throws Exception {
            Session s = sf.openSession();
            Transaction tx = s.beginTransaction();
            State france = getState(s, "Ile de France");
            Criteria criteria = s.createCriteria(NonBasicCitizen.class);
            criteria.add(Restrictions.naturalId().set("ssn", "1234").set("state", france));
            criteria.setCacheable(true);
            NonBasicCitizen c = (NonBasicCitizen) criteria.uniqueResult();
            s.delete(c);
            // cleanup
            tx.commit();
            s.close();
            return null;
         }
      });
   }

   private State getState(Session s, String name) {
      Criteria criteria = s.createCriteria(State.class);
      criteria.add(Restrictions.eq("name", name));
      criteria.setCacheable(true);
      return (State) criteria.list().get(0);
   }

   @Listener
   public static class MyListener {

      private static final Log log = LogFactory.getLog(MyListener.class);
      private Set<String> visited = new ConcurrentSet<String>();
      private final String name;

      public MyListener(String name) {
         this.name = name;
      }

      public void clear() {
         visited.clear();
      }

      public boolean isEmpty() {
         touched.set(false);
         return visited.isEmpty();
      }

      @CacheEntryInvalidated
      public void nodeInvalidated(CacheEntryInvalidatedEvent event) {
         log.debug(event.toString());
         if (!event.isPre()) {

            Object cacheKey = event.getKey();
            visited.remove(cacheKey.toString());
            String add = "";
            for (String vis : visited) {
               add += "\n" + vis;
               touched.set(true);
            }
//             assertTrue(add, false);
//             CitizenPK primKey = (CitizenPK) cacheKey.getKey();
//             String key = (String) cacheKey.getEntityOrRoleName() + '#' + primKey;
//             log.debug( "MyListener[" + name + "] - Visiting key " + key );
//             // String name = fqn.toString();
//             String token = ".functional.";
//             int index = key.indexOf( token );
//             if ( index > -1 ) {
//                index += token.length();
//                key = key.substring( index );
//                log.debug( "MyListener[" + name + "] - recording visit to " + key );
//                visited.add( key );
//             }
         }
      }

      @CacheEntryVisited
      public void nodeVisited(CacheEntryVisitedEvent event) {
         log.debug(event.toString());
         if (!event.isPre()) {

            Object cacheKey = event.getKey();
            visited.add(cacheKey.toString());
            String add = "";
            for (String vis : visited) {
               add += "\n" + vis;
               touched.set(true);
            }
//            assertTrue(add, false);
//            CitizenPK primKey = (CitizenPK) cacheKey.getKey();
//            String key = (String) cacheKey.getEntityOrRoleName() + '#' + primKey;
//            log.debug( "MyListener[" + name + "] - Visiting key " + key );
//            // String name = fqn.toString();
//            String token = ".functional.";
//            int index = key.indexOf( token );
//            if ( index > -1 ) {
//               index += token.length();
//               key = key.substring( index );
//               log.debug( "MyListener[" + name + "] - recording visit to " + key );
//               visited.add( key );
//            }
         }
      }
   }

}
