//$Id$
package org.hibernate.test.cache.infinispan.functional;
import java.io.Serializable;

import org.hibernate.annotations.NaturalId;
import org.hibernate.annotations.NaturalIdCache;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

import javax.persistence.Entity;
import javax.persistence.EmbeddedId;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.persistence.Cacheable;

/**
 * @author Emmanuel Bernard
 */
@Entity
@Cacheable
@Cache(usage=CacheConcurrencyStrategy.TRANSACTIONAL)
public class NonBasicCitizen implements Serializable{
//	@Id
//	private Integer id;
	@EmbeddedId
	private CitizenPK id;
	private String firstname;
	private String lastname;
	
	@ManyToOne
	private State state;
	
	private String ssn;


	public CitizenPK getId() {
		return id;
	}

	public void setId(CitizenPK id) {
		this.id = id;
	}

	public String getFirstname() {
		return firstname;
	}

	public void setFirstname(String firstname) {
		this.firstname = firstname;
	}

	public String getLastname() {
		return lastname;
	}

	public void setLastname(String lastname) {
		this.lastname = lastname;
	}

	public State getState() {
		return state;
	}

	public void setState(State state) {
		this.state = state;
	}

	public String getSsn() {
		return ssn;
	}

	public void setSsn(String ssn) {
		this.ssn = ssn;
	}
}
