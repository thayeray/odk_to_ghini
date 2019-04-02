package odk_to_ghini;

public class Added
{	private String accessionCode;
	private String metaInstanceID;
	
	Added (String metaInstanceId)
	{	this.metaInstanceID = metaInstanceId;
	}
	
	Added (String metaInstanceId, String accessionCode)
	{	this.metaInstanceID = metaInstanceId;
		this.accessionCode = accessionCode;
	}
	
	public String getAccessionCode() {
		return accessionCode;
	}

	public void setAccessionCode(String accessionCode) {
		this.accessionCode = accessionCode;
	}

	public String getMetaInstanceID() {
		return metaInstanceID;
	}

	public void setMetaInstanceID(String metaInstanceID) {
		this.metaInstanceID = metaInstanceID;
	}

	public boolean equals(Object e)
	{	if (metaInstanceID.equals(((Added) e).getMetaInstanceID()))
			return true;
		else return false;
	}
	
	public String toString()  
	{	return accessionCode + "\t" + metaInstanceID;
	}
}