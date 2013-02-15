/**
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.bitcoin.core;

import com.google.bitcoin.core.Transaction.SigHash;
import com.google.bitcoin.core.WalletTransaction.Pool;
import com.google.bitcoin.store.BlockStore;
import com.google.bitcoin.store.MemoryBlockStore;
import com.google.bitcoin.utils.BriefLogFormatter;
import com.google.common.collect.Lists;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.google.bitcoin.core.TestUtils.*;
import static com.google.bitcoin.core.Utils.bitcoinValueToFriendlyString;
import static com.google.bitcoin.core.Utils.toNanoCoins;
import static org.junit.Assert.*;

public class WalletTest {
    static final NetworkParameters params = NetworkParameters.unitTests();

    private Address myAddress;
    private Wallet wallet;
    private BlockChain chain;
    private BlockStore blockStore;
    private ECKey myKey;

    @Before
    public void setUp() throws Exception {
        myKey = new ECKey();
        myAddress = myKey.toAddress(params);
        wallet = new Wallet(params);
        wallet.addKey(myKey);
        blockStore = new MemoryBlockStore(params);
        chain = new BlockChain(params, wallet, blockStore);
        BriefLogFormatter.init();
    }

    private Transaction sendMoneyToWallet(Transaction tx, AbstractBlockChain.NewBlockType type)
            throws IOException, ProtocolException, VerificationException {
        BlockPair bp = createFakeBlock(blockStore, tx);
        wallet.receiveFromBlock(tx, bp.storedBlock, type);
        if (type == AbstractBlockChain.NewBlockType.BEST_CHAIN)
            wallet.notifyNewBestBlock(bp.block);
        return tx;
    }

    private Transaction sendMoneyToWallet(BigInteger value, AbstractBlockChain.NewBlockType type)
            throws IOException, ProtocolException, VerificationException {
        return sendMoneyToWallet(createFakeTx(params, value, myAddress), type);
    }

    @Test
    public void basicSpending() throws Exception {
        // We'll set up a wallet that receives a coin, then sends a coin of lesser value and keeps the change. We
        // will attach a small fee. Because the Bitcoin protocol makes it difficult to determine the fee of an
        // arbitrary transaction in isolation, we'll check that the fee was set by examining the size of the change.

        // Receive some money.
        BigInteger v1 = Utils.toNanoCoins(1, 0);
        sendMoneyToWallet(v1, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        assertEquals(v1, wallet.getBalance());
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.ALL));

        // Create a send with a fee.
        Address destination = new ECKey().toAddress(params);
        BigInteger v2 = toNanoCoins(0, 50);
        Wallet.SendRequest req = Wallet.SendRequest.to(destination, v2);
        req.fee = toNanoCoins(0, 1);
        wallet.completeTx(req);
        Transaction t2 = req.tx;
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.ALL));

        // Do some basic sanity checks.
        assertEquals(1, t2.getInputs().size());
        assertEquals(myAddress, t2.getInputs().get(0).getScriptSig().getFromAddress());
        assertEquals(t2.getConfidence().getConfidenceType(), TransactionConfidence.ConfidenceType.NOT_SEEN_IN_CHAIN);
        assertEquals(2, t2.getOutputs().size());
        assertEquals(destination, t2.getOutputs().get(0).getScriptPubKey().getToAddress());
        assertEquals(wallet.getChangeAddress(), t2.getOutputs().get(1).getScriptPubKey().getToAddress());
        assertEquals(toNanoCoins(0, 49), t2.getOutputs().get(1).getValue());
        // Check the script runs and signatures verify.
        t2.getInputs().get(0).verify();

        final LinkedList<Transaction> txns = Lists.newLinkedList();
        wallet.addEventListener(new AbstractWalletEventListener() {
            @Override
            public void onCoinsSent(Wallet wallet, Transaction tx, BigInteger prevBalance, BigInteger newBalance) {
                txns.add(tx);
            }
        });
        wallet.commitTx(t2);
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.PENDING));
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.SPENT));
        assertEquals(2, wallet.getPoolSize(WalletTransaction.Pool.ALL));
        assertEquals(t2, txns.getFirst());
        assertEquals(1, txns.size());
    }

    @Test
    public void customTransactionSpending() throws Exception {
        // We'll set up a wallet that receives a coin, then sends a coin of lesser value and keeps the change.
        BigInteger v1 = Utils.toNanoCoins(3, 0);
        sendMoneyToWallet(v1, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        assertEquals(v1, wallet.getBalance());
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.ALL));

        ECKey k2 = new ECKey();
        Address a2 = k2.toAddress(params);
        BigInteger v2 = toNanoCoins(0, 50);
        BigInteger v3 = toNanoCoins(0, 75);
        BigInteger v4 = toNanoCoins(1, 25);

        Transaction t2 = new Transaction(params);
        t2.addOutput(v2, a2);
        t2.addOutput(v3, a2);
        t2.addOutput(v4, a2);
        boolean complete = wallet.completeTx(Wallet.SendRequest.forTx(t2));

        // Do some basic sanity checks.
        assertTrue(complete);
        assertEquals(1, t2.getInputs().size());
        assertEquals(myAddress, t2.getInputs().get(0).getScriptSig().getFromAddress());
        assertEquals(t2.getConfidence().getConfidenceType(), TransactionConfidence.ConfidenceType.NOT_SEEN_IN_CHAIN);

        // We have NOT proven that the signature is correct!
        wallet.commitTx(t2);
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.PENDING));
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.SPENT));
        assertEquals(2, wallet.getPoolSize(WalletTransaction.Pool.ALL));
    }

    @Test
    public void sideChain() throws Exception {
        // The wallet receives a coin on the main chain, then on a side chain. Only main chain counts towards balance.
        BigInteger v1 = Utils.toNanoCoins(1, 0);
        sendMoneyToWallet(v1, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        assertEquals(v1, wallet.getBalance());
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.ALL));

        BigInteger v2 = toNanoCoins(0, 50);
        sendMoneyToWallet(v2, AbstractBlockChain.NewBlockType.SIDE_CHAIN);
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.INACTIVE));
        assertEquals(2, wallet.getPoolSize(WalletTransaction.Pool.ALL));

        assertEquals(v1, wallet.getBalance());
    }

    @Test
    public void balance() throws Exception {
        // Receive 5 coins then half a coin.
        BigInteger v1 = toNanoCoins(5, 0);
        BigInteger v2 = toNanoCoins(0, 50);
        BigInteger expected = toNanoCoins(5, 50);
        assertEquals(0, wallet.getPoolSize(WalletTransaction.Pool.ALL));
        sendMoneyToWallet(v1, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        sendMoneyToWallet(v2, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        assertEquals(2, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        assertEquals(expected, wallet.getBalance());

        // Now spend one coin.
        BigInteger v3 = toNanoCoins(1, 0);
        Transaction spend = wallet.createSend(new ECKey().toAddress(params), v3);
        wallet.commitTx(spend);
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.PENDING));

        // Available and estimated balances should not be the same. We don't check the exact available balance here
        // because it depends on the coin selection algorithm.
        assertEquals(toNanoCoins(4, 50), wallet.getBalance(Wallet.BalanceType.ESTIMATED));
        assertFalse(wallet.getBalance(Wallet.BalanceType.AVAILABLE).equals(
                    wallet.getBalance(Wallet.BalanceType.ESTIMATED)));

        // Now confirm the transaction by including it into a block.
        StoredBlock b3 = createFakeBlock(blockStore, spend).storedBlock;
        wallet.receiveFromBlock(spend, b3, BlockChain.NewBlockType.BEST_CHAIN);

        // Change is confirmed. We started with 5.50 so we should have 4.50 left.
        BigInteger v4 = toNanoCoins(4, 50);
        assertEquals(v4, wallet.getBalance(Wallet.BalanceType.AVAILABLE));
    }

    // Intuitively you'd expect to be able to create a transaction with identical inputs and outputs and get an
    // identical result to the official client. However the signatures are not deterministic - signing the same data
    // with the same key twice gives two different outputs. So we cannot prove bit-for-bit compatibility in this test
    // suite.

    @Test
    public void blockChainCatchup() throws Exception {
        // Test that we correctly process transactions arriving from the chain, with callbacks for inbound and outbound.
        final BigInteger bigints[] = new BigInteger[4];
        final Transaction txn[] = new Transaction[2];
        final LinkedList<Transaction> confTxns = new LinkedList<Transaction>();
        wallet.addEventListener(new AbstractWalletEventListener() {
            @Override
            public void onCoinsReceived(Wallet wallet, Transaction tx, BigInteger prevBalance, BigInteger newBalance) {
                super.onCoinsReceived(wallet, tx, prevBalance, newBalance);
                bigints[0] = prevBalance;
                bigints[1] = newBalance;
                txn[0] = tx;
            }

            @Override
            public void onCoinsSent(Wallet wallet, Transaction tx, BigInteger prevBalance, BigInteger newBalance) {
                super.onCoinsSent(wallet, tx, prevBalance, newBalance);
                bigints[2] = prevBalance;
                bigints[3] = newBalance;
                txn[1] = tx;
            }

            @Override
            public void onTransactionConfidenceChanged(Wallet wallet, Transaction tx) {
                super.onTransactionConfidenceChanged(wallet, tx);
                confTxns.add(tx);
            }
        });
        
        // Receive some money.
        BigInteger oneCoin = Utils.toNanoCoins(1, 0);
        Transaction tx1 = sendMoneyToWallet(oneCoin, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        assertEquals(null, txn[1]);  // onCoinsSent not called.
        assertEquals(tx1, confTxns.getFirst());   // onTransactionConfidenceChanged called
        assertEquals(txn[0].getHash(), tx1.getHash());
        assertEquals(BigInteger.ZERO, bigints[0]);
        assertEquals(oneCoin, bigints[1]);
        assertEquals(TransactionConfidence.ConfidenceType.BUILDING, tx1.getConfidence().getConfidenceType());
        assertEquals(1, tx1.getConfidence().getAppearedAtChainHeight());
        // Send 0.10 to somebody else.
        Transaction send1 = wallet.createSend(new ECKey().toAddress(params), toNanoCoins(0, 10));
        // Pretend it makes it into the block chain, our wallet state is cleared but we still have the keys, and we
        // want to get back to our previous state. We can do this by just not confirming the transaction as
        // createSend is stateless.
        txn[0] = txn[1] = null;
        confTxns.clear();
        sendMoneyToWallet(send1, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        assertEquals(bitcoinValueToFriendlyString(wallet.getBalance()), "0.90");
        assertEquals(null, txn[0]);
        assertEquals(2, confTxns.size());
        assertEquals(txn[1].getHash(), send1.getHash());
        assertEquals(bitcoinValueToFriendlyString(bigints[2]), "1.00");
        assertEquals(bitcoinValueToFriendlyString(bigints[3]), "0.90");
        // And we do it again after the catchup.
        Transaction send2 = wallet.createSend(new ECKey().toAddress(params), toNanoCoins(0, 10));
        // What we'd really like to do is prove the official client would accept it .... no such luck unfortunately.
        wallet.commitTx(send2);
        sendMoneyToWallet(send2, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        assertEquals(bitcoinValueToFriendlyString(wallet.getBalance()), "0.80");
        Block b4 = createFakeBlock(blockStore).block;
        confTxns.clear();
        wallet.notifyNewBestBlock(b4);
        assertEquals(3, confTxns.size());
    }

    @Test
    public void balances() throws Exception {
        BigInteger nanos = Utils.toNanoCoins(1, 0);
        Transaction tx1 = sendMoneyToWallet(nanos, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        assertEquals(nanos, tx1.getValueSentToMe(wallet, true));
        // Send 0.10 to somebody else.
        Transaction send1 = wallet.createSend(new ECKey().toAddress(params), toNanoCoins(0, 10));
        // Reserialize.
        Transaction send2 = new Transaction(params, send1.bitcoinSerialize());
        assertEquals(nanos, send2.getValueSentFromMe(wallet));
        assertEquals(BigInteger.ZERO.subtract(toNanoCoins(0, 10)), send2.getValue(wallet));
    }

    @Test
    public void isConsistent_duplicates() throws Exception {
        // This test ensures that isConsistent catches duplicate transactions, eg, because we submitted the same block
        // twice (this is not allowed).
        Transaction tx = createFakeTx(params, Utils.toNanoCoins(1, 0), myAddress);
        Address someOtherGuy = new ECKey().toAddress(params);
        TransactionOutput output = new TransactionOutput(params, tx, Utils.toNanoCoins(0, 5), someOtherGuy);
        tx.addOutput(output);
        wallet.receiveFromBlock(tx, null, BlockChain.NewBlockType.BEST_CHAIN);
        
        assertTrue(wallet.isConsistent());
        
        Transaction txClone = new Transaction(params, tx.bitcoinSerialize());
        try {
            wallet.receiveFromBlock(txClone, null, BlockChain.NewBlockType.BEST_CHAIN);
            fail();
        } catch (IllegalStateException ex) {
            // expected
        }
    }

    @Test
    public void isConsistent_pools() throws Exception {
        // This test ensures that isConsistent catches transactions that are in incompatible pools.
        Transaction tx = createFakeTx(params, Utils.toNanoCoins(1, 0), myAddress);
        Address someOtherGuy = new ECKey().toAddress(params);
        TransactionOutput output = new TransactionOutput(params, tx, Utils.toNanoCoins(0, 5), someOtherGuy);
        tx.addOutput(output);
        wallet.receiveFromBlock(tx, null, BlockChain.NewBlockType.BEST_CHAIN);
        
        assertTrue(wallet.isConsistent());
        
        wallet.addWalletTransaction(new WalletTransaction(Pool.PENDING, tx));
        assertFalse(wallet.isConsistent());
    }

    @Test
    public void isConsistent_spent() throws Exception {
        // This test ensures that isConsistent catches transactions that are marked spent when
        // they aren't.
        Transaction tx = createFakeTx(params, Utils.toNanoCoins(1, 0), myAddress);
        Address someOtherGuy = new ECKey().toAddress(params);
        TransactionOutput output = new TransactionOutput(params, tx, Utils.toNanoCoins(0, 5), someOtherGuy);
        tx.addOutput(output);
        assertTrue(wallet.isConsistent());
        
        wallet.addWalletTransaction(new WalletTransaction(Pool.SPENT, tx));
        assertFalse(wallet.isConsistent());
    }

    @Test
    public void transactions() throws Exception {
        // This test covers a bug in which Transaction.getValueSentFromMe was calculating incorrectly.
        Transaction tx = createFakeTx(params, Utils.toNanoCoins(1, 0), myAddress);
        // Now add another output (ie, change) that goes to some other address.
        Address someOtherGuy = new ECKey().toAddress(params);
        TransactionOutput output = new TransactionOutput(params, tx, Utils.toNanoCoins(0, 5), someOtherGuy);
        tx.addOutput(output);
        // Note that tx is no longer valid: it spends more than it imports. However checking transactions balance
        // correctly isn't possible in SPV mode because value is a property of outputs not inputs. Without all
        // transactions you can't check they add up.
        sendMoneyToWallet(tx, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        // Now the other guy creates a transaction which spends that change.
        Transaction tx2 = new Transaction(params);
        tx2.addInput(output);
        tx2.addOutput(new TransactionOutput(params, tx2, Utils.toNanoCoins(0, 5), myAddress));
        // tx2 doesn't send any coins from us, even though the output is in the wallet.
        assertEquals(Utils.toNanoCoins(0, 0), tx2.getValueSentFromMe(wallet));
    }

    @Test
    public void bounce() throws Exception {
        // This test covers bug 64 (False double spends). Check that if we create a spend and it's immediately sent
        // back to us, this isn't considered as a double spend.
        BigInteger coin1 = Utils.toNanoCoins(1, 0);
        sendMoneyToWallet(coin1, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        // Send half to some other guy. Sending only half then waiting for a confirm is important to ensure the tx is
        // in the unspent pool, not pending or spent.
        BigInteger coinHalf = Utils.toNanoCoins(0, 50);
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.ALL));
        Address someOtherGuy = new ECKey().toAddress(params);
        Transaction outbound1 = wallet.createSend(someOtherGuy, coinHalf);
        wallet.commitTx(outbound1);
        sendMoneyToWallet(outbound1, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        // That other guy gives us the coins right back.
        Transaction inbound2 = new Transaction(params);
        inbound2.addOutput(new TransactionOutput(params, inbound2, coinHalf, myAddress));
        inbound2.addInput(outbound1.getOutputs().get(0));
        sendMoneyToWallet(inbound2, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        assertEquals(coin1, wallet.getBalance());
    }

    @Test
    public void doubleSpendFinneyAttack() throws Exception {
        // A Finney attack is where a miner includes a transaction spending coins to themselves but does not
        // broadcast it. When they find a solved block, they hold it back temporarily whilst they buy something with
        // those same coins. After purchasing, they broadcast the block thus reversing the transaction. It can be
        // done by any miner for products that can be bought at a chosen time and very quickly (as every second you
        // withold your block means somebody else might find it first, invalidating your work).
        //
        // Test that we handle the attack correctly: a double spend on the chain moves transactions from pending to dead.
        // This needs to work both for transactions we create, and that we receive from others.
        final Transaction[] eventDead = new Transaction[1];
        final Transaction[] eventReplacement = new Transaction[1];
        final int[] eventWalletChanged = new int[1];
        wallet.addEventListener(new AbstractWalletEventListener() {
            @Override
            public void onTransactionConfidenceChanged(Wallet wallet, Transaction tx) {
                super.onTransactionConfidenceChanged(wallet, tx);
                if (tx.getConfidence().getConfidenceType() ==
                        TransactionConfidence.ConfidenceType.DEAD) {
                    eventDead[0] = tx;
                    eventReplacement[0] = tx.getConfidence().getOverridingTransaction();
                }
            }

            @Override
            public void onWalletChanged(Wallet wallet) {
                eventWalletChanged[0]++;
            }
        });

        // Receive 1 BTC.
        BigInteger nanos = Utils.toNanoCoins(1, 0);
        sendMoneyToWallet(nanos, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        // Create a send to a merchant.
        Transaction send1 = wallet.createSend(new ECKey().toAddress(params), toNanoCoins(0, 50));
        // Create a double spend.
        Transaction send2 = wallet.createSend(new ECKey().toAddress(params), toNanoCoins(0, 50));
        send2 = new Transaction(params, send2.bitcoinSerialize());
        // Broadcast send1.
        wallet.commitTx(send1);
        // Receive a block that overrides it.
        sendMoneyToWallet(send2, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        assertEquals(send1, eventDead[0]);
        assertEquals(send2, eventReplacement[0]);
        assertEquals(TransactionConfidence.ConfidenceType.DEAD,
                     send1.getConfidence().getConfidenceType());
        
        TestUtils.DoubleSpends doubleSpends = TestUtils.createFakeDoubleSpendTxns(params, myAddress);
        // t1 spends to our wallet. t2 double spends somewhere else.
        wallet.receivePending(doubleSpends.t1, null);
        assertEquals(TransactionConfidence.ConfidenceType.NOT_SEEN_IN_CHAIN,
                doubleSpends.t1.getConfidence().getConfidenceType());
        sendMoneyToWallet(doubleSpends.t2, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        assertEquals(TransactionConfidence.ConfidenceType.DEAD,
                     doubleSpends.t1.getConfidence().getConfidenceType());
        assertEquals(doubleSpends.t2, doubleSpends.t1.getConfidence().getOverridingTransaction());
        assertEquals(5, eventWalletChanged[0]);
    }

    @Test
    public void pending1() throws Exception {
        // Check that if we receive a pending transaction that is then confirmed, we are notified as appropriate.
        final BigInteger nanos = Utils.toNanoCoins(1, 0);
        final Transaction t1 = createFakeTx(params, nanos, myAddress);

        // First one is "called" second is "pending".
        final boolean[] flags = new boolean[2];
        final Transaction[] notifiedTx = new Transaction[1];
        final int[] walletChanged = new int[1];
        wallet.addEventListener(new AbstractWalletEventListener() {
            @Override
            public void onCoinsReceived(Wallet wallet, Transaction tx, BigInteger prevBalance, BigInteger newBalance) {
                // Check we got the expected transaction.
                assertEquals(tx, t1);
                // Check that it's considered to be pending inclusion in the block chain.
                assertEquals(prevBalance, BigInteger.ZERO);
                assertEquals(newBalance, nanos);
                flags[0] = true;
                flags[1] = tx.isPending();
                notifiedTx[0] = tx;
            }

            @Override
            public void onWalletChanged(Wallet wallet) {
                walletChanged[0]++;
            }
        });

        if (wallet.isPendingTransactionRelevant(t1))
            wallet.receivePending(t1, null);
        assertTrue(flags[0]);
        assertTrue(flags[1]);   // is pending
        flags[0] = false;
        // Check we don't get notified if we receive it again.
        assertFalse(wallet.isPendingTransactionRelevant(t1));
        assertFalse(flags[0]);
        // Now check again, that we should NOT be notified when we receive it via a block (we were already notified).
        // However the confidence should be updated.
        // Make a fresh copy of the tx to ensure we're testing realistically.
        flags[0] = flags[1] = false;
        notifiedTx[0].getConfidence().addEventListener(new TransactionConfidence.Listener() {
            public void onConfidenceChanged(Transaction tx) {
                flags[1] = true;
            }
        });
        assertEquals(TransactionConfidence.ConfidenceType.NOT_SEEN_IN_CHAIN,
                notifiedTx[0].getConfidence().getConfidenceType());
        final Transaction t1Copy = new Transaction(params, t1.bitcoinSerialize());
        sendMoneyToWallet(t1Copy, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        assertFalse(flags[0]);
        assertTrue(flags[1]);
        assertEquals(TransactionConfidence.ConfidenceType.BUILDING, notifiedTx[0].getConfidence().getConfidenceType());
        // Check we don't get notified about an irrelevant transaction.
        flags[0] = false;
        flags[1] = false;
        Transaction irrelevant = createFakeTx(params, nanos, new ECKey().toAddress(params));
        if (wallet.isPendingTransactionRelevant(irrelevant))
            wallet.receivePending(irrelevant, null);
        assertFalse(flags[0]);
        assertEquals(2, walletChanged[0]);
    }

    @Test
    public void pending2() throws Exception {
        // Check that if we receive a pending tx we did not send, it updates our spent flags correctly.
        final Transaction txn[] = new Transaction[1];
        final BigInteger bigints[] = new BigInteger[2];
        wallet.addEventListener(new AbstractWalletEventListener() {
            @Override
            public void onCoinsSent(Wallet wallet, Transaction tx, BigInteger prevBalance, BigInteger newBalance) {
                txn[0] = tx;
                bigints[0] = prevBalance;
                bigints[1] = newBalance;
            }
        });
        // Receive some coins.
        BigInteger nanos = Utils.toNanoCoins(1, 0);
        sendMoneyToWallet(nanos, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        // Create a spend with them, but don't commit it (ie it's from somewhere else but using our keys). This TX
        // will have change as we don't spend our entire balance.
        BigInteger halfNanos = Utils.toNanoCoins(0, 50);
        Transaction t2 = wallet.createSend(new ECKey().toAddress(params), halfNanos);
        // Now receive it as pending.
        if (wallet.isPendingTransactionRelevant(t2))
            wallet.receivePending(t2, null);
        // We received an onCoinsSent() callback.
        assertEquals(t2, txn[0]);
        assertEquals(nanos, bigints[0]);
        assertEquals(halfNanos, bigints[1]);
        // Our balance is now 0.50 BTC
        assertEquals(halfNanos, wallet.getBalance(Wallet.BalanceType.ESTIMATED));
    }

    @Test
    public void pending3() throws Exception {
        // Check that if we receive a pending tx, and it's overridden by a double spend from the main chain, we
        // are notified that it's dead. This should work even if the pending tx inputs are NOT ours, ie, they don't
        // connect to anything.
        BigInteger nanos = Utils.toNanoCoins(1, 0);

        // Create two transactions that share the same input tx.
        Address badGuy = new ECKey().toAddress(params);
        Transaction doubleSpentTx = new Transaction(params);
        TransactionOutput doubleSpentOut = new TransactionOutput(params, doubleSpentTx, nanos, badGuy);
        doubleSpentTx.addOutput(doubleSpentOut);
        Transaction t1 = new Transaction(params);
        TransactionOutput o1 = new TransactionOutput(params, t1, nanos, myAddress);
        t1.addOutput(o1);
        t1.addInput(doubleSpentOut);
        Transaction t2 = new Transaction(params);
        TransactionOutput o2 = new TransactionOutput(params, t2, nanos, badGuy);
        t2.addOutput(o2);
        t2.addInput(doubleSpentOut);

        final Transaction[] called = new Transaction[2];
        wallet.addEventListener(new AbstractWalletEventListener() {
            public void onCoinsReceived(Wallet wallet, Transaction tx, BigInteger prevBalance, BigInteger newBalance) {
                called[0] = tx;
            }

            @Override
            public void onTransactionConfidenceChanged(Wallet wallet, Transaction tx) {
                super.onTransactionConfidenceChanged(wallet, tx);
                if (tx.getConfidence().getConfidenceType() == 
                        TransactionConfidence.ConfidenceType.DEAD) {
                    called[0] = tx;
                    called[1] = tx.getConfidence().getOverridingTransaction();
                }
            }
        });

        assertEquals(BigInteger.ZERO, wallet.getBalance());
        if (wallet.isPendingTransactionRelevant(t1))
            wallet.receivePending(t1, null);
        assertEquals(t1, called[0]);
        assertEquals(nanos, wallet.getBalance(Wallet.BalanceType.ESTIMATED));
        // Now receive a double spend on the main chain.
        called[0] = called[1] = null;
        sendMoneyToWallet(t2, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        assertEquals(BigInteger.ZERO, wallet.getBalance());
        assertEquals(t1, called[0]); // dead
        assertEquals(t2, called[1]); // replacement
    }

    @Test
    public void transactionsList() throws Exception {
        // Check the wallet can give us an ordered list of all received transactions.
        Utils.rollMockClock(0);
        Transaction tx1 = sendMoneyToWallet(Utils.toNanoCoins(1, 0), AbstractBlockChain.NewBlockType.BEST_CHAIN);
        Utils.rollMockClock(60 * 10);
        Transaction tx2 = sendMoneyToWallet(Utils.toNanoCoins(0, 5), AbstractBlockChain.NewBlockType.BEST_CHAIN);
        // Check we got them back in order.
        List<Transaction> transactions = wallet.getTransactionsByTime();
        assertEquals(tx2, transactions.get(0));
        assertEquals(tx1,  transactions.get(1));
        assertEquals(2, transactions.size());
        // Check we get only the last transaction if we request a subrage.
        transactions = wallet.getRecentTransactions(1, false);
        assertEquals(1, transactions.size());
        assertEquals(tx2,  transactions.get(0));

        // Create a spend five minutes later.
        Utils.rollMockClock(60 * 5);
        Transaction tx3 = wallet.createSend(new ECKey().toAddress(params), Utils.toNanoCoins(0, 5));
        // Does not appear in list yet.
        assertEquals(2, wallet.getTransactionsByTime().size());
        wallet.commitTx(tx3);
        // Now it does.
        transactions = wallet.getTransactionsByTime();
        assertEquals(3, transactions.size());
        assertEquals(tx3, transactions.get(0));

        // Verify we can handle the case of older wallets in which the timestamp is null (guessed from the
        // block appearances list).
        tx1.setUpdateTime(null);
        tx3.setUpdateTime(null);
        // Check we got them back in order.
        transactions = wallet.getTransactionsByTime();
        assertEquals(tx2,  transactions.get(0));
        assertEquals(3, transactions.size());
    }

    @Test
    public void keyCreationTime() throws Exception {
        wallet = new Wallet(params);
        long now = Utils.rollMockClock(0).getTime() / 1000;  // Fix the mock clock.
        // No keys returns current time.
        assertEquals(now, wallet.getEarliestKeyCreationTime());
        Utils.rollMockClock(60);
        wallet.addKey(new ECKey());
        assertEquals(now + 60, wallet.getEarliestKeyCreationTime());
        Utils.rollMockClock(60);
        wallet.addKey(new ECKey());
        assertEquals(now + 60, wallet.getEarliestKeyCreationTime());
    }

    @Test
    public void spendToSameWallet() throws Exception {
        // Test that a spend to the same wallet is dealt with correctly.
        // It should appear in the wallet and confirm.
        // This is a bit of a silly thing to do in the real world as all it does is burn a fee but it is perfectly valid.
        BigInteger coin1 = Utils.toNanoCoins(1, 0);
        BigInteger coinHalf = Utils.toNanoCoins(0, 50);
        // Start by giving us 1 coin.
        sendMoneyToWallet(coin1, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        // Send half to ourselves. We should then have a balance available to spend of zero.
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.ALL));
        Transaction outbound1 = wallet.createSend(myAddress, coinHalf);
        wallet.commitTx(outbound1);
        // We should have a zero available balance before the next block.
        assertEquals(BigInteger.ZERO, wallet.getBalance());
        sendMoneyToWallet(outbound1, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        // We should have a balance of 1 BTC after the block is received.
        assertEquals(coin1, wallet.getBalance());
    }

    @Test
    public void rememberLastBlockSeenHash() throws Exception {
        BigInteger v1 = toNanoCoins(5, 0);
        BigInteger v2 = toNanoCoins(0, 50);
        BigInteger v3 = toNanoCoins(0, 25);
        Transaction t1 = createFakeTx(params, v1, myAddress);
        Transaction t2 = createFakeTx(params, v2, myAddress);
        Transaction t3 = createFakeTx(params, v3, myAddress);

        Block genesis = blockStore.getChainHead().getHeader();
        Block b10 = makeSolvedTestBlock(genesis, t1);
        Block b11 = makeSolvedTestBlock(genesis, t2);
        Block b2 = makeSolvedTestBlock(b10, t3);
        Block b3 = makeSolvedTestBlock(b2);

        // Receive a block on the best chain - this should set the last block seen hash.
        chain.add(b10);
        assertEquals(b10.getHash(), wallet.getLastBlockSeenHash());
        // Receive a block on the side chain - this should not change the last block seen hash.
        chain.add(b11);
        assertEquals(b10.getHash(), wallet.getLastBlockSeenHash());
        // Receive block 2 on the best chain - this should change the last block seen hash.
        chain.add(b2);
        assertEquals(b2.getHash(), wallet.getLastBlockSeenHash());
        // Receive block 3 on the best chain - this should change the last block seen hash despite having no txns.
        chain.add(b3);
        assertEquals(b3.getHash(), wallet.getLastBlockSeenHash());
    }

    @Test
    public void pubkeyOnlyScripts() throws Exception {
        // Verify that we support outputs like OP_PUBKEY and the corresponding inputs.
        ECKey key1 = new ECKey();
        wallet.addKey(key1);
        BigInteger value = toNanoCoins(5, 0);
        Transaction t1 = createFakeTx(params, value, key1);
        if (wallet.isPendingTransactionRelevant(t1))
            wallet.receivePending(t1, null);
        // TX should have been seen as relevant.
        assertEquals(value, wallet.getBalance(Wallet.BalanceType.ESTIMATED));
        assertEquals(BigInteger.ZERO, wallet.getBalance(Wallet.BalanceType.AVAILABLE));
        Block b1 = createFakeBlock(blockStore, t1).block;
        chain.add(b1);
        // TX should have been seen as relevant, extracted and processed.
        assertEquals(value, wallet.getBalance(Wallet.BalanceType.AVAILABLE));
        // Spend it and ensure we can spend the <key> OP_CHECKSIG output correctly.
        Transaction t2 = wallet.createSend(new ECKey().toAddress(params), value);
        assertNotNull(t2);
        // TODO: This code is messy, improve the Script class and fixinate!
        assertEquals(t2.toString(), 1, t2.getInputs().get(0).getScriptSig().chunks.size());
        assertTrue(t2.getInputs().get(0).getScriptSig().chunks.get(0).data.length > 50);
        System.out.println(t2);
    }

    @Test
    public void autosaveImmediate() throws Exception {
        // Test that the wallet will save itself automatically when it changes.
        File f = File.createTempFile("bitcoinj-unit-test", null);
        Sha256Hash hash1 = Sha256Hash.hashFileContents(f);
        // Start with zero delay and ensure the wallet file changes after adding a key.
        wallet.autosaveToFile(f, 0, TimeUnit.SECONDS, null);
        ECKey key = new ECKey();
        wallet.addKey(key);
        Sha256Hash hash2 = Sha256Hash.hashFileContents(f);
        assertFalse(hash1.equals(hash2));  // File has changed.

        Transaction t1 = createFakeTx(params, toNanoCoins(5, 0), key);
        if (wallet.isPendingTransactionRelevant(t1))
            wallet.receivePending(t1, null);
        Sha256Hash hash3 = Sha256Hash.hashFileContents(f);
        assertFalse(hash2.equals(hash3));  // File has changed again.

        Block b1 = createFakeBlock(blockStore, t1).block;
        chain.add(b1);
        Sha256Hash hash4 = Sha256Hash.hashFileContents(f);
        assertFalse(hash3.equals(hash4));  // File has changed again.

        // Check that receiving some block without any relevant transactions still triggers a save.
        Block b2 = b1.createNextBlock(new ECKey().toAddress(params));
        chain.add(b2);
        assertFalse(hash4.equals(Sha256Hash.hashFileContents(f)));  // File has changed again.
    }

    @Test
    public void autosaveDelayed() throws Exception {
        // Test that the wallet will save itself automatically when it changes, but not immediately and near-by
        // updates are coalesced together. This test is a bit racy, it assumes we can complete the unit test within
        // an auto-save cycle of 1 second.
        final File[] results = new File[2];
        final CountDownLatch latch = new CountDownLatch(2);
        File f = File.createTempFile("bitcoinj-unit-test", null);
        Sha256Hash hash1 = Sha256Hash.hashFileContents(f);
        wallet.autosaveToFile(f, 1, TimeUnit.SECONDS,
                new Wallet.AutosaveEventListener() {
                    public boolean caughtException(Throwable t) {
                        return false;
                    }

                    public void onBeforeAutoSave(File tempFile) {
                        results[0] = tempFile;
                    }

                    public void onAfterAutoSave(File newlySavedFile) {
                        results[1] = newlySavedFile;
                        latch.countDown();
                    }
                }
        );
        ECKey key = new ECKey();
        wallet.addKey(key);
        Sha256Hash hash2 = Sha256Hash.hashFileContents(f);
        assertFalse(hash1.equals(hash2));  // File has changed immediately despite the delay, as keys are important.
        assertNotNull(results[0]);
        assertEquals(f, results[1]);
        results[0] = results[1] = null;

        Transaction t1 = createFakeTx(params, toNanoCoins(5, 0), key);
        if (wallet.isPendingTransactionRelevant(t1))
            wallet.receivePending(t1, null);
        Sha256Hash hash3 = Sha256Hash.hashFileContents(f);
        assertTrue(hash2.equals(hash3));  // File has NOT changed.
        assertNull(results[0]);
        assertNull(results[1]);

        Block b1 = createFakeBlock(blockStore, t1).block;
        chain.add(b1);
        Sha256Hash hash4 = Sha256Hash.hashFileContents(f);
        assertTrue(hash3.equals(hash4));  // File has NOT changed.
        assertNull(results[0]);
        assertNull(results[1]);

        Block b2 = b1.createNextBlock(new ECKey().toAddress(params));
        chain.add(b2);
        assertTrue(hash4.equals(Sha256Hash.hashFileContents(f)));  // File has NOT changed.
        assertNull(results[0]);
        assertNull(results[1]);

        // Wait for an auto-save to occur.
        latch.await();
        assertFalse(hash4.equals(Sha256Hash.hashFileContents(f)));  // File has now changed.
        assertNotNull(results[0]);
        assertEquals(f, results[1]);
    }
    
    @Test
    public void spendOutputFromPendingTransaction() throws Exception {
    	// We'll set up a wallet that receives a coin, then sends a coin of lesser value and keeps the change.
        BigInteger v1 = Utils.toNanoCoins(1, 0);
        sendMoneyToWallet(v1, AbstractBlockChain.NewBlockType.BEST_CHAIN);
        // First create our current transaction
        ECKey k2 = new ECKey();
        wallet.addKey(k2);
        BigInteger v2 = toNanoCoins(0, 50);
        Transaction t2 = new Transaction(params);
        TransactionOutput o2 = new TransactionOutput(params, t2, v2, k2.toAddress(params));
        t2.addOutput(o2);
        boolean complete = wallet.completeTx(Wallet.SendRequest.forTx(t2));
        assertTrue(complete);
        
        // Commit t2, so it is placed in the pending pool
        wallet.commitTx(t2);
        assertEquals(0, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        assertEquals(1, wallet.getPoolSize(WalletTransaction.Pool.PENDING));
        assertEquals(2, wallet.getPoolSize(WalletTransaction.Pool.ALL));
        
        // Now try the spend the output
        ECKey k3 = new ECKey();
        BigInteger v3 = toNanoCoins(0, 25);
        Transaction t3 = new Transaction(params);
        t3.addOutput(v3, k3.toAddress(params));
        t3.addInput(o2);
        t3.signInputs(SigHash.ALL, wallet);
        
        // Commit t3, so the coins from the pending t2 are spent
        wallet.commitTx(t3);
        assertEquals(0, wallet.getPoolSize(WalletTransaction.Pool.UNSPENT));
        assertEquals(2, wallet.getPoolSize(WalletTransaction.Pool.PENDING));
        assertEquals(3, wallet.getPoolSize(WalletTransaction.Pool.ALL));
        
        // Now the output of t2 must not be available for spending
        assertFalse(o2.isAvailableForSpending());
    }

    // There is a test for spending a coinbase transaction as it matures in BlockChainTest#coinbaseTransactionAvailability

    // Support for offline spending is tested in PeerGroupTest
}
