package mixer.protocol;

public strictfp interface SignatureReceivedHandler {
	
	void signatureReceived(int index, String signature);
}
