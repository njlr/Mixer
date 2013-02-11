package mixer.protocol.messages;

import com.google.common.base.Preconditions;

public strictfp final class MessageTransaction extends Message {
	
	private static final long serialVersionUID = 9211193151437744281L;
	
	private final String transaction;
	
	public String getTransaction() {
		
		return this.transaction;
	}
	
	public MessageTransaction(final String transaction) {
		
		super();
		
		Preconditions.checkArgument(transaction != null);
		
		this.transaction = transaction;
	}
	
	@Override
	public String toString() {
		
		return "MessageTransaction[transaction: " + this.transaction.toString() + "]";
	}
}
