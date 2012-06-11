package org.hibernate.test.cache.infinispan.functional;

import java.io.Serializable;
import javax.persistence.GeneratedValue;
import javax.persistence.Embeddable;

@Embeddable
public final class CitizenPK implements Serializable {
	//@Column(name="id")
    public CitizenPK() {
        _id = 1;
    }

    public CitizenPK(Integer id) {
        _id = id;
    }

    @Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		CitizenPK other = (CitizenPK) obj;
		if (_id == null) {
			if (other._id != null)
				return false;
		} else if (!_id.equals(other._id))
			return false;
		return true;
	}

    @Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((_id == null) ? 0 : _id.hashCode());
		return result;
	}

    public String toInteger() {
        return "id=["+_id+"]";
    }


    //Getter/setters
    public Integer getId() {
        return _id;
    }
    public void setId(Integer id) {
        _id = id;
    }

    /**************************************************
     * Implementation
     */

    private static final long serialVersionUID = 1L;
	
	@GeneratedValue
    private Integer _id;

}
