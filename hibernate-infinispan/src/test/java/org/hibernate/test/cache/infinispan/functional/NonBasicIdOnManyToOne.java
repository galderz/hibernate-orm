package org.hibernate.test.cache.infinispan.functional;

import org.hibernate.annotations.NaturalId;
import org.hibernate.annotations.NaturalIdCache;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.ManyToOne;

@Entity
@NaturalIdCache
/**
 * Test case for NaturalId annotation - ANN-750
 * 
 * @author Emmanuel Bernard
 * @author Hardy Ferentschik
 */
public class NonBasicIdOnManyToOne {

    @Id
    @GeneratedValue
    int id;

    @NaturalId
    @ManyToOne
    NonBasicCitizen citizen;

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public NonBasicCitizen getCitizen() {
		return citizen;
	}

	public void setCitizen(NonBasicCitizen citizen) {
		this.citizen = citizen;
	}
} 
