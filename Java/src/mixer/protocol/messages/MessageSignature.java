package mixer.protocol.messages;

import com.google.common.base.Preconditions;

public strictfp final class MessageSignature extends Message {
	
	private static final long serialVersionUID = -8944727153638179974L;
	
	private final byte[] signature;
	
	public byte[] getSignature() {
		
		return this.signature;
	}
	
	public MessageSignature(final byte[] signature) {
		
		super();
		
		Preconditions.checkArgument(signature != null);
		
		this.signature = signature;
	}
	
	@Override
	public String toString() {
		
		return "MessageSignature[signature: " + this.signature.toString() + "]";
	}
}
