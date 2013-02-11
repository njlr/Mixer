package mixer.protocol;

import java.util.Set;

public strictfp interface InputReceivedHandler {
	
	void inputReceived(int index, String oldAddress, Set<String> freshAddress) throws Exception;
}
