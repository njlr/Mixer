package mixer.protocol.messages;

public strictfp final class MessageTransaction extends Message {
	
	private static final long serialVersionUID = 9211193151437744281L;
	
	private final String transaction;
	
	public String getTransaction() {
		
		return this.transaction;
	}
	
	public MessageTransaction(String transaction) {
		
		super();
		
		this.transaction = transaction;
	}
}
