package mixer.protocol.messages;

public strictfp final class MessageSignature extends Message {
	
	private static final long serialVersionUID = -8944727153638179974L;
	
	private final String signature;
	
	public String getSignature() {
		
		return this.signature;
	}
	
	public MessageSignature(String signature) {
		
		super();
		
		this.signature = signature;
	}
}
