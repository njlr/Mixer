package mixer.protocol.messages;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

public strictfp final class MessagePlayerInput extends Message {
	
	private static final long serialVersionUID = 7437481367004735495L;
	
	private final String oldAddress;
	private final Set<String> freshAddresses;
	
	public String getOldAddress() {
		
		return this.oldAddress;
	}
	
	public Set<String> getFreshAddresses() {
		
		return ImmutableSet.copyOf(this.freshAddresses);
	}
	
	public MessagePlayerInput(String oldAddress, Set<String> freshAddresses) {
		
		super();
		
		this.oldAddress = oldAddress;
		this.freshAddresses = ImmutableSet.copyOf(freshAddresses);
	}
}
