/*
 * Copyright 2012 Google Inc.
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

import com.google.bitcoin.store.BlockStore;
import com.google.bitcoin.store.BlockStoreException;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.*;

import static com.google.common.base.Preconditions.*;

/**
 * <p>An AbstractBlockChain holds a series of {@link Block} objects, links them together, and knows how to verify that
 * the chain follows the rules of the {@link NetworkParameters} for this chain.</p>
 *
 * <p>It can be connected to a {@link Wallet}, and also {@link BlockChainListener}s that can receive transactions and
 * notifications of re-organizations.</p>
 *
 * <p>An AbstractBlockChain implementation must be connected to a {@link BlockStore} implementation. The chain object
 * by itself doesn't store any data, that's delegated to the store. Which store you use is a decision best made by
 * reading the getting started guide, but briefly, fully validating block chains need fully validating stores. In
 * the lightweight SPV mode, a {@link com.google.bitcoin.store.BoundedOverheadBlockStore} may be a good choice.</p>
 *
 * <p>This class implements an abstract class which makes it simple to create a BlockChain that does/doesn't do full
 * verification.  It verifies headers and is implements most of what is required to implement SPV mode, but
 * also provides callback hooks which can be used to do full verification.</p>
 *
 * <p>There are two subclasses of AbstractBlockChain that are useful: {@link BlockChain}, which is the simplest
 * class and implements <i>simplified payment verification</i>. This is a lightweight and efficient mode that does
 * not verify the contents of blocks, just their headers. A {@link FullPrunedBlockChain} paired with a
 * {@link com.google.bitcoin.store.H2FullPrunedBlockStore} implements full verification, which is equivalent to the
 * original Satoshi client. To learn more about the alternative security models, please consult the articles on the
 * website.</p>
 *
 * <b>Theory</b>
 *
 * <p>The 'chain' is actually a tree although in normal operation it operates mostly as a list of {@link Block}s.
 * When multiple new head blocks are found simultaneously, there are multiple stories of the economy competing to become
 * the one true consensus. This can happen naturally when two miners solve a block within a few seconds of each other,
 * or it can happen when the chain is under attack.</p>
 *
 * <p>A reference to the head block of the best known chain is stored. If you can reach the genesis block by repeatedly
 * walking through the prevBlock pointers, then we say this is a full chain. If you cannot reach the genesis block
 * we say it is an orphan chain. Orphan chains can occur when blocks are solved and received during the initial block
 * chain download, or if we connect to a peer that doesn't send us blocks in order.</p>
 *
 * <p>A reorganize occurs when the blocks that make up the best known chain changes. Note that simply adding a
 * new block to the top of the best chain isn't as reorganize, but that a reorganize is always triggered by adding
 * a new block that connects to some other (non best head) block. By "best" we mean the chain representing the largest
 * amount of work done.</p>
 *
 * <p>Every so often the block chain passes a difficulty transition point. At that time, all the blocks in the last
 * 2016 blocks are examined and a new difficulty target is calculated from them.</p>
 */
public abstract class AbstractBlockChain {
    private static final Logger log = LoggerFactory.getLogger(AbstractBlockChain.class);

    /** Keeps a map of block hashes to StoredBlocks. */
    private final BlockStore blockStore;

    /**
     * Tracks the top of the best known chain.<p>
     *
     * Following this one down to the genesis block produces the story of the economy from the creation of BitCoin
     * until the present day. The chain head can change if a new set of blocks is received that results in a chain of
     * greater work than the one obtained by following this one down. In that case a reorganize is triggered,
     * potentially invalidating transactions in our wallet.
     */
    protected StoredBlock chainHead;

    // The chainHead field is read/written synchronized with this object rather than BlockChain. However writing is
    // also guaranteed to happen whilst BlockChain is synchronized (see setChainHead). The goal of this is to let
    // clients quickly access the chain head even whilst the block chain is downloading and thus the BlockChain is
    // locked most of the time.
    private final Object chainHeadLock = new Object();

    protected final NetworkParameters params;
    private final List<BlockChainListener> listeners;

    // Holds a block header and, optionally, a list of tx hashes or block's transactions
    class OrphanBlock {
        Block block;
        Set<Sha256Hash> filteredTxHashes;
        List<Transaction> filteredTxn;
        OrphanBlock(Block block, Set<Sha256Hash> filteredTxHashes, List<Transaction> filteredTxn) {
            Preconditions.checkArgument((block.transactions == null && filteredTxHashes != null && filteredTxn != null)
                    || (block.transactions != null && filteredTxHashes == null && filteredTxn == null));
            this.block = block;
            this.filteredTxHashes = filteredTxHashes;
            this.filteredTxn = filteredTxn;
        }
    }
    // Holds blocks that we have received but can't plug into the chain yet, eg because they were created whilst we
    // were downloading the block chain.
    private final LinkedHashMap<Sha256Hash, OrphanBlock> orphanBlocks = new LinkedHashMap<Sha256Hash, OrphanBlock>();

    /**
     * Constructs a BlockChain connected to the given list of listeners (eg, wallets) and a store.
     */
    public AbstractBlockChain(NetworkParameters params, List<BlockChainListener> listeners,
                          BlockStore blockStore) throws BlockStoreException {
        this.blockStore = blockStore;
        chainHead = blockStore.getChainHead();
        log.info("chain head is at height {}:\n{}", chainHead.getHeight(), chainHead.getHeader());
        this.params = params;
        this.listeners = new ArrayList<BlockChainListener>(listeners);
    }

    /**
     * Add a wallet to the BlockChain. Note that the wallet will be unaffected by any blocks received while it
     * was not part of this BlockChain. This method is useful if the wallet has just been created, and its keys
     * have never been in use, or if the wallet has been loaded along with the BlockChain. Note that adding multiple
     * wallets is not well tested!
     */
    public synchronized void addWallet(Wallet wallet) {
        listeners.add(wallet);
    }

    /**
     * Adds a generic {@link BlockChainListener} listener to the chain.
     */
    public synchronized void addListener(BlockChainListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes the given {@link BlockChainListener} from the chain.
     */
    public synchronized void removeListener(BlockChainListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Returns the {@link BlockStore} the chain was constructed with. You can use this to iterate over the chain.
     */
    public BlockStore getBlockStore() {
        return blockStore;
    }
    
    /**
     * Adds/updates the given {@link Block} with the block store.
     * This version is used when the transactions have not been verified.
     * @param storedPrev The {@link StoredBlock} which immediately precedes block.
     * @param block The {@link Block} to add/update.
     * @returns the newly created {@link StoredBlock}
     */
    protected abstract StoredBlock addToBlockStore(StoredBlock storedPrev, Block block)
            throws BlockStoreException, VerificationException;
    
    /**
     * Adds/updates the given {@link StoredBlock} with the block store.
     * This version is used when the transactions have already been verified to properly spend txOutputChanges.
     * @param storedPrev The {@link StoredBlock} which immediately precedes block.
     * @param header The {@link StoredBlock} to add/update.
     * @param txOutputChanges The total sum of all changes made by this block to the set of open transaction outputs (from a call to connectTransactions)
     * @returns the newly created {@link StoredBlock}
     */
    protected abstract StoredBlock addToBlockStore(StoredBlock storedPrev, Block header,
                                                   TransactionOutputChanges txOutputChanges)
            throws BlockStoreException, VerificationException;
    
    /**
     * Called before setting chain head in memory.
     * Should write the new head to block store and then commit any database transactions
     * that were started by disconnectTransactions/connectTransactions.
     */
    protected abstract void doSetChainHead(StoredBlock chainHead) throws BlockStoreException;
    
    /**
     * Called if we (possibly) previously called disconnectTransaction/connectTransactions,
     * but will not be calling preSetChainHead as a block failed verification.
     * Can be used to abort database transactions that were started by
     * disconnectTransactions/connectTransactions.
     */
    protected abstract void notSettingChainHead() throws BlockStoreException;
    
    /**
     * For a standard BlockChain, this should return blockStore.get(hash),
     * for a FullPrunedBlockChain blockStore.getOnceUndoableStoredBlock(hash)
     */
    protected abstract StoredBlock getStoredBlockInCurrentScope(Sha256Hash hash) throws BlockStoreException;

    /**
     * Processes a received block and tries to add it to the chain. If there's something wrong with the block an
     * exception is thrown. If the block is OK but cannot be connected to the chain at this time, returns false.
     * If the block can be connected to the chain, returns true.
     */
    public synchronized boolean add(Block block) throws VerificationException, PrunedException {
        try {
            return add(block, null, null, true);
        } catch (BlockStoreException e) {
            // TODO: Figure out a better way to propagate this exception to the user.
            throw new RuntimeException(e);
        } catch (VerificationException e) {
            try {
                notSettingChainHead();
            } catch (BlockStoreException e1) {
                throw new RuntimeException(e1);
            }
            throw new VerificationException("Could not verify block " + block.getHashAsString() + "\n" +
                    block.toString(), e);
        }
    }
    
    /**
     * Processes a received block and tries to add it to the chain. If there's something wrong with the block an
     * exception is thrown. If the block is OK but cannot be connected to the chain at this time, returns false.
     * If the block can be connected to the chain, returns true.
     */
    public synchronized boolean add(FilteredBlock block) throws VerificationException, PrunedException {
        try {
            Set<Sha256Hash> filteredTxnHashSet = new HashSet<Sha256Hash>(block.getTransactionHashes());
            List<Transaction> filteredTxn = block.getAssociatedTransactions();
            for (Transaction tx : filteredTxn) {
                Preconditions.checkState(filteredTxnHashSet.remove(tx.getHash()));
            }
            return add(block.getBlockHeader(), filteredTxnHashSet, filteredTxn, true);
        } catch (BlockStoreException e) {
            // TODO: Figure out a better way to propagate this exception to the user.
            throw new RuntimeException(e);
        } catch (VerificationException e) {
            try {
                notSettingChainHead();
            } catch (BlockStoreException e1) {
                throw new RuntimeException(e1);
            }
            throw new VerificationException("Could not verify block " + block.getHash().toString() + "\n" +
                    block.toString(), e);
        }
    }
    
    /**
     * Whether or not we are maintaining a set of unspent outputs and are verifying all transactions.
     * Also indicates that all calls to add() should provide a block containing transactions
     */
    protected abstract boolean shouldVerifyTransactions();
    
    /**
     * Connect each transaction in block.transactions, verifying them as we go and removing spent outputs
     * If an error is encountered in a transaction, no changes should be made to the underlying BlockStore.
     * and a VerificationException should be thrown.
     * Only called if(shouldVerifyTransactions())
     * @throws VerificationException if an attempt was made to spend an already-spent output, or if a transaction incorrectly solved an output script.
     * @throws BlockStoreException if the block store had an underlying error.
     * @return The full set of all changes made to the set of open transaction outputs.
     */
    protected abstract TransactionOutputChanges connectTransactions(int height, Block block) throws VerificationException, BlockStoreException;

    /**
     * Load newBlock from BlockStore and connect its transactions, returning changes to the set of unspent transactions.
     * If an error is encountered in a transaction, no changes should be made to the underlying BlockStore.
     * Only called if(shouldVerifyTransactions())
     * @throws PrunedException if newBlock does not exist as a {@link StoredUndoableBlock} in the block store.
     * @throws VerificationException if an attempt was made to spend an already-spent output, or if a transaction incorrectly solved an output script.
     * @throws BlockStoreException if the block store had an underlying error or newBlock does not exist in the block store at all.
     * @return The full set of all changes made to the set of open transaction outputs.
     */
    protected abstract TransactionOutputChanges connectTransactions(StoredBlock newBlock) throws VerificationException, BlockStoreException, PrunedException;    
    
    // Stat counters.
    private long statsLastTime = System.currentTimeMillis();
    private long statsBlocksAdded;

    // filteredTxHashList and filteredTxn[i].GetHash() should be mutually exclusive
    private synchronized boolean add(Block block, Set<Sha256Hash> filteredTxHashList, List<Transaction> filteredTxn, boolean tryConnecting)
            throws BlockStoreException, VerificationException, PrunedException {
        // Note on locking: this method runs with the block chain locked. All mutations to the chain are serialized.
        // This has the undesirable consequence that during block chain download, it's slow to read the current chain
        // head and other chain info because the accessors are constantly waiting for the chain to become free. To
        // solve this things viewable via accessors must use fine-grained locking as well as being mutated under the
        // chain lock.
        if (System.currentTimeMillis() - statsLastTime > 1000) {
            // More than a second passed since last stats logging.
            if (statsBlocksAdded > 1)
                log.info("{} blocks per second", statsBlocksAdded);
            statsLastTime = System.currentTimeMillis();
            statsBlocksAdded = 0;
        }
        // Quick check for duplicates to avoid an expensive check further down (in findSplit). This can happen a lot
        // when connecting orphan transactions due to the dumb brute force algorithm we use.
        if (block.equals(getChainHead().getHeader())) {
            return true;
        }
        if (tryConnecting && orphanBlocks.containsKey(block.getHash())) {
            return false;
        }
        
        // If we want to verify transactions (ie we are running with full blocks), verify that block has transactions
        if (shouldVerifyTransactions() && block.transactions == null)
            throw new VerificationException("Got a block header while running in full-block mode");

        // Does this block contain any transactions we might care about? Check this up front before verifying the
        // blocks validity so we can skip the merkle root verification if the contents aren't interesting. This saves
        // a lot of time for big blocks.
        boolean contentsImportant = shouldVerifyTransactions();
        if (block.transactions != null) {
            contentsImportant = contentsImportant || containsRelevantTransactions(block);
        }

        // Prove the block is internally valid: hash is lower than target, etc. This only checks the block contents
        // if there is a tx sending or receiving coins using an address in one of our wallets. And those transactions
        // are only lightly verified: presence in a valid connecting block is taken as proof of validity. See the
        // article here for more details: http://code.google.com/p/bitcoinj/wiki/SecurityModel
        try {
            block.verifyHeader();
            if (contentsImportant)
                block.verifyTransactions();
        } catch (VerificationException e) {
            log.error("Failed to verify block: ", e);
            log.error(block.getHashAsString());
            throw e;
        }

        // Try linking it to a place in the currently known blocks.
        StoredBlock storedPrev = getStoredBlockInCurrentScope(block.getPrevBlockHash());

        if (storedPrev == null) {
            // We can't find the previous block. Probably we are still in the process of downloading the chain and a
            // block was solved whilst we were doing it. We put it to one side and try to connect it later when we
            // have more blocks.
            checkState(tryConnecting, "bug in tryConnectingOrphans");
            log.warn("Block does not connect: {} prev {}", block.getHashAsString(), block.getPrevBlockHash());
            orphanBlocks.put(block.getHash(), new OrphanBlock(block, filteredTxHashList, filteredTxn));
            return false;
        } else {
            // It connects to somewhere on the chain. Not necessarily the top of the best known chain.
            //
            // Create a new StoredBlock from this block. It will throw away the transaction data so when block goes
            // out of scope we will reclaim the used memory.
            checkDifficultyTransitions(storedPrev, block);
            connectBlock(block, storedPrev, shouldVerifyTransactions(), filteredTxHashList, filteredTxn);
        }

        if (tryConnecting)
            tryConnectingOrphans();

        statsBlocksAdded++;
        return true;
    }

    // expensiveChecks enables checks that require looking at blocks further back in the chain
    // than the previous one when connecting (eg median timestamp check)
    // It could be exposed, but for now we just set it to shouldVerifyTransactions()
    private void connectBlock(Block block, StoredBlock storedPrev, boolean expensiveChecks,
            Set<Sha256Hash> filteredTxHashList, List<Transaction> filteredTxn)
            throws BlockStoreException, VerificationException, PrunedException {
        // Check that we aren't connecting a block that fails a checkpoint check
        if (!params.passesCheckpoint(storedPrev.getHeight() + 1, block.getHash()))
            throw new VerificationException("Block failed checkpoint lockin at " + (storedPrev.getHeight() + 1));
        if (shouldVerifyTransactions())
            for (Transaction tx : block.transactions)
                if (!tx.isFinal(storedPrev.getHeight() + 1, block.getTimeSeconds()))
                   throw new VerificationException("Block contains non-final transaction");
        
        StoredBlock head = getChainHead();
        if (storedPrev.equals(head)) {
            if (expensiveChecks && block.getTimeSeconds() <= getMedianTimestampOfRecentBlocks(head))
                throw new VerificationException("Block's timestamp is too early");
            
            // This block connects to the best known block, it is a normal continuation of the system.
            TransactionOutputChanges txOutChanges = null;
            if (shouldVerifyTransactions())
                txOutChanges = connectTransactions(storedPrev.getHeight() + 1, block);
            StoredBlock newStoredBlock = addToBlockStore(storedPrev, block.cloneAsHeader(), txOutChanges);
            setChainHead(newStoredBlock);
            log.debug("Chain is now {} blocks high", newStoredBlock.getHeight());
            // Notify the listeners of the new block, so the depth and workDone of stored transactions can be updated
            // (in the case of the listener being a wallet). Wallets need to know how deep each transaction is so
            // coinbases aren't used before maturity.
            for (int i = 0; i < listeners.size(); i++) {
                BlockChainListener listener = listeners.get(i);
                if (block.transactions != null || filteredTxn != null) {
                    // If this is not the first wallet, ask for the transactions to be duplicated before being given
                    // to the wallet when relevant. This ensures that if we have two connected wallets and a tx that
                    // is relevant to both of them, they don't end up accidentally sharing the same object (which can
                    // result in temporary in-memory corruption during re-orgs). See bug 257. We only duplicate in
                    // the case of multiple wallets to avoid an unnecessary efficiency hit in the common case.
                    sendTransactionsToListener(newStoredBlock, NewBlockType.BEST_CHAIN, listener,
                            block.transactions != null ? block.transactions : filteredTxn, i > 0);
                }
                if (filteredTxHashList != null) {
                    for (Sha256Hash hash : filteredTxHashList) {
                        listener.notifyTransactionIsInBlock(hash, newStoredBlock, NewBlockType.BEST_CHAIN);
                    }
                }
                // Allow the listener to have removed itself.
                if (i == listeners.size()) {
                    break;  // Listener removed itself and it was the last one.
                } else if (listeners.get(i) != listener) {
                    i--;  // Listener removed itself and it was not the last one.
                    break;
                }
                listener.notifyNewBestBlock(newStoredBlock.getHeader());
                if (i == listeners.size()) {
                    break;  // Listener removed itself and it was the last one.
                } else if (listeners.get(i) != listener) {
                    i--;  // Listener removed itself and it was not the last one.
                    break;
                }
            }
        } else {
            // This block connects to somewhere other than the top of the best known chain. We treat these differently.
            //
            // Note that we send the transactions to the wallet FIRST, even if we're about to re-organize this block
            // to become the new best chain head. This simplifies handling of the re-org in the Wallet class.
            StoredBlock newBlock = storedPrev.build(block);
            boolean haveNewBestChain = newBlock.moreWorkThan(head);
            if (haveNewBestChain) {
                log.info("Block is causing a re-organize");
            } else {
                StoredBlock splitPoint = findSplit(newBlock, head);
                if (splitPoint != null && splitPoint.equals(newBlock)) {
                    // newStoredBlock is a part of the same chain, there's no fork. This happens when we receive a block
                    // that we already saw and linked into the chain previously, which isn't the chain head.
                    // Re-processing it is confusing for the wallet so just skip.
                    log.warn("Saw duplicated block in main chain at height {}: {}",
                            newBlock.getHeight(), newBlock.getHeader().getHash());
                    return;
                }
                if (splitPoint == null) {
                    // This should absolutely never happen
                    // (lets not write the full block to disk to keep any bugs which allow this to happen
                    //  from writing unreasonable amounts of data to disk)
                    throw new VerificationException("Block forks the chain but splitPoint is null");
                } else {
                    // We aren't actually spending any transactions (yet) because we are on a fork
                    addToBlockStore(storedPrev, block);
                    int splitPointHeight = splitPoint.getHeight();
                    String splitPointHash = splitPoint.getHeader().getHashAsString();
                    log.info("Block forks the chain at height {}/block {}, but it did not cause a reorganize:\n{}",
                        new Object[]{splitPointHeight, splitPointHash, newBlock});
                }
            }
            
            // We may not have any transactions if we received only a header, which can happen during fast catchup.
            // If we do, send them to the wallet but state that they are on a side chain so it knows not to try and
            // spend them until they become activated.
            if (block.transactions != null || filteredTxn != null) {
                for (int i = 0; i < listeners.size(); i++) {
                    BlockChainListener listener = listeners.get(i);
                    List<Transaction> txnToNotify;
                    if (block.transactions != null)
                        txnToNotify = block.transactions;
                    else
                        txnToNotify = filteredTxn;
                    // If this is not the first wallet, ask for the transactions to be duplicated before being given
                    // to the wallet when relevant. This ensures that if we have two connected wallets and a tx that
                    // is relevant to both of them, they don't end up accidentally sharing the same object (which can
                    // result in temporary in-memory corruption during re-orgs). See bug 257. We only duplicate in
                    // the case of multiple wallets to avoid an unnecessary efficiency hit in the common case.
                    sendTransactionsToListener(newBlock, NewBlockType.SIDE_CHAIN, listener, txnToNotify, i > 0);
                    if (filteredTxHashList != null) {
                        for (Sha256Hash hash : filteredTxHashList) {
                            listener.notifyTransactionIsInBlock(hash, newBlock, NewBlockType.SIDE_CHAIN);
                        }
                    }
                    if (i == listeners.size()) {
                        break;  // Listener removed itself and it was the last one.
                    } else if (listeners.get(i) != listener) {
                        i--;  // Listener removed itself and it was not the last one.
                    }
                }
            }
            
            if (haveNewBestChain)
                handleNewBestChain(storedPrev, newBlock, block, expensiveChecks);
        }
    }
    
    /**
     * Gets the median timestamp of the last 11 blocks
     */
    private long getMedianTimestampOfRecentBlocks(StoredBlock storedBlock) throws BlockStoreException {
        long[] timestamps = new long[11];
        int unused = 9;
        timestamps[10] = storedBlock.getHeader().getTimeSeconds();
        while (unused >= 0 && (storedBlock = storedBlock.getPrev(blockStore)) != null)
            timestamps[unused--] = storedBlock.getHeader().getTimeSeconds();
        
        Arrays.sort(timestamps, unused+1, 10);
        return timestamps[unused + (11-unused)/2];
    }
    
    /**
     * Disconnect each transaction in the block (after reading it from the block store)
     * Only called if(shouldVerifyTransactions())
     * @throws PrunedException if block does not exist as a {@link StoredUndoableBlock} in the block store.
     * @throws BlockStoreException if the block store had an underlying error or block does not exist in the block store at all.
     */
    protected abstract void disconnectTransactions(StoredBlock block) throws PrunedException, BlockStoreException;

    /**
     * Called as part of connecting a block when the new block results in a different chain having higher total work.
     * 
     * if (shouldVerifyTransactions)
     *     Either newChainHead needs to be in the block store as a FullStoredBlock, or (block != null && block.transactions != null)
     */
    private void handleNewBestChain(StoredBlock storedPrev, StoredBlock newChainHead, Block block, boolean expensiveChecks)
            throws BlockStoreException, VerificationException, PrunedException {
        // This chain has overtaken the one we currently believe is best. Reorganize is required.
        //
        // Firstly, calculate the block at which the chain diverged. We only need to examine the
        // chain from beyond this block to find differences.
        StoredBlock head = getChainHead();
        StoredBlock splitPoint = findSplit(newChainHead, head);
        log.info("Re-organize after split at height {}", splitPoint.getHeight());
        log.info("Old chain head: {}", head.getHeader().getHashAsString());
        log.info("New chain head: {}", newChainHead.getHeader().getHashAsString());
        log.info("Split at block: {}", splitPoint.getHeader().getHashAsString());
        // Then build a list of all blocks in the old part of the chain and the new part.
        LinkedList<StoredBlock> oldBlocks = getPartialChain(head, splitPoint);
        LinkedList<StoredBlock> newBlocks = getPartialChain(newChainHead, splitPoint);
        // Disconnect each transaction in the previous main chain that is no longer in the new main chain
        StoredBlock storedNewHead = splitPoint;
        if (shouldVerifyTransactions()) {
            for (StoredBlock oldBlock : oldBlocks) {
                try {
                    disconnectTransactions(oldBlock);
                } catch (PrunedException e) {
                    // We threw away the data we need to re-org this deep! We need to go back to a peer with full
                    // block contents and ask them for the relevant data then rebuild the indexs. Or we could just
                    // give up and ask the human operator to help get us unstuck (eg, rescan from the genesis block).
                    // TODO: Retry adding this block when we get a block with hash e.getHash()
                    throw e;
                }
            }
            StoredBlock cursor;
            // Walk in ascending chronological order.
            for (Iterator<StoredBlock> it = newBlocks.descendingIterator(); it.hasNext();) {
                cursor = it.next();
                if (expensiveChecks && cursor.getHeader().getTimeSeconds() <= getMedianTimestampOfRecentBlocks(cursor.getPrev(blockStore)))
                    throw new VerificationException("Block's timestamp is too early during reorg");
                TransactionOutputChanges txOutChanges;
                if (cursor != newChainHead || block == null)
                    txOutChanges = connectTransactions(cursor);
                else
                    txOutChanges = connectTransactions(newChainHead.getHeight(), block);
                storedNewHead = addToBlockStore(storedNewHead, cursor.getHeader(), txOutChanges);
            }
        } else {
            // (Finally) write block to block store
            storedNewHead = addToBlockStore(storedPrev, newChainHead.getHeader());
        }
        // Now inform the listeners. This is necessary so the set of currently active transactions (that we can spend)
        // can be updated to take into account the re-organize. We might also have received new coins we didn't have
        // before and our previous spends might have been undone.
        for (int i = 0; i < listeners.size(); i++) {
            BlockChainListener listener = listeners.get(i);
            listener.reorganize(splitPoint, oldBlocks, newBlocks);
            if (i == listeners.size()) {
                break;  // Listener removed itself and it was the last one.
            } else if (listeners.get(i) != listener) {
                i--;  // Listener removed itself and it was not the last one.
            }
        }
        // Update the pointer to the best known block.
        setChainHead(storedNewHead);
    }

    /**
     * Returns the set of contiguous blocks between 'higher' and 'lower'. Higher is included, lower is not.
     */
    private LinkedList<StoredBlock> getPartialChain(StoredBlock higher, StoredBlock lower) throws BlockStoreException {
        checkArgument(higher.getHeight() > lower.getHeight(), "higher and lower are reversed");
        LinkedList<StoredBlock> results = new LinkedList<StoredBlock>();
        StoredBlock cursor = higher;
        while (true) {
            results.add(cursor);
            cursor = checkNotNull(cursor.getPrev(blockStore), "Ran off the end of the chain");
            if (cursor.equals(lower)) break;
        }
        return results;
    }

    /**
     * Locates the point in the chain at which newStoredBlock and chainHead diverge. Returns null if no split point was
     * found (ie they are not part of the same chain). Returns newChainHead or chainHead if they don't actually diverge
     * but are part of the same chain.
     */
    private StoredBlock findSplit(StoredBlock newChainHead, StoredBlock oldChainHead) throws BlockStoreException {
        StoredBlock currentChainCursor = oldChainHead;
        StoredBlock newChainCursor = newChainHead;
        // Loop until we find the block both chains have in common. Example:
        //
        //    A -> B -> C -> D
        //         \--> E -> F -> G
        //
        // findSplit will return block B. oldChainHead = D and newChainHead = G.
        while (!currentChainCursor.equals(newChainCursor)) {
            if (currentChainCursor.getHeight() > newChainCursor.getHeight()) {
                currentChainCursor = currentChainCursor.getPrev(blockStore);
                checkNotNull(currentChainCursor, "Attempt to follow an orphan chain");
            } else {
                newChainCursor = newChainCursor.getPrev(blockStore);
                checkNotNull(newChainCursor, "Attempt to follow an orphan chain");
            }
        }
        return currentChainCursor;
    }

    /**
     * @return the height of the best known chain, convenience for <tt>getChainHead().getHeight()</tt>.
     */
    public int getBestChainHeight() {
        return getChainHead().getHeight();
    }

    public enum NewBlockType {
        BEST_CHAIN,
        SIDE_CHAIN
    }

    private void sendTransactionsToListener(StoredBlock block, NewBlockType blockType, BlockChainListener listener,
                                            List<Transaction> transactions, boolean clone) throws VerificationException {
        for (Transaction tx : transactions) {
            try {
                if (listener.isTransactionRelevant(tx)) {
                    if (clone)
                        tx = new Transaction(tx.params, tx.bitcoinSerialize());
                    listener.receiveFromBlock(tx, block, blockType);
                }
            } catch (ScriptException e) {
                // We don't want scripts we don't understand to break the block chain so just note that this tx was
                // not scanned here and continue.
                log.warn("Failed to parse a script: " + e.toString());
            } catch (ProtocolException e) {
                // Failed to duplicate tx, should never happen.
                throw new RuntimeException(e);
            }
        }
    }
    
    private void setChainHead(StoredBlock chainHead) throws BlockStoreException {
        doSetChainHead(chainHead);
        synchronized (chainHeadLock) {
            this.chainHead = chainHead;
        }
    }

    /**
     * For each block in orphanBlocks, see if we can now fit it on top of the chain and if so, do so.
     */
    private void tryConnectingOrphans() throws VerificationException, BlockStoreException, PrunedException {
        // For each block in our orphan list, try and fit it onto the head of the chain. If we succeed remove it
        // from the list and keep going. If we changed the head of the list at the end of the round try again until
        // we can't fit anything else on the top.
        //
        // This algorithm is kind of crappy, we should do a topo-sort then just connect them in order, but for small
        // numbers of orphan blocks it does OK.
        int blocksConnectedThisRound;
        do {
            blocksConnectedThisRound = 0;
            Iterator<OrphanBlock> iter = orphanBlocks.values().iterator();
            while (iter.hasNext()) {
                OrphanBlock orphanBlock = iter.next();
                log.debug("Trying to connect {}", orphanBlock.block.getHash());
                // Look up the blocks previous.
                StoredBlock prev = getStoredBlockInCurrentScope(orphanBlock.block.getPrevBlockHash());
                if (prev == null) {
                    // This is still an unconnected/orphan block.
                    log.debug("  but it is not connectable right now");
                    continue;
                }
                // Otherwise we can connect it now.
                // False here ensures we don't recurse infinitely downwards when connecting huge chains.
                add(orphanBlock.block, orphanBlock.filteredTxHashes, orphanBlock.filteredTxn, false);
                iter.remove();
                blocksConnectedThisRound++;
            }
            if (blocksConnectedThisRound > 0) {
                log.info("Connected {} orphan blocks.", blocksConnectedThisRound);
            }
        } while (blocksConnectedThisRound > 0);
    }

    // February 16th 2012
    private static Date testnetDiffDate = new Date(1329264000000L);

    /**
     * Throws an exception if the blocks difficulty is not correct.
     */
    private void checkDifficultyTransitions(StoredBlock storedPrev, Block nextBlock)
            throws BlockStoreException, VerificationException {
        Block prev = storedPrev.getHeader();
        
        // Is this supposed to be a difficulty transition point?
        if ((storedPrev.getHeight() + 1) % params.interval != 0) {

            // TODO: Refactor this hack after 0.5 is released and we stop supporting deserialization compatibility.
            // This should be a method of the NetworkParameters, which should in turn be using singletons and a subclass
            // for each network type. Then each network can define its own difficulty transition rules.
            if (params.getId().equals(NetworkParameters.ID_TESTNET) && nextBlock.getTime().after(testnetDiffDate)) {
                checkTestnetDifficulty(storedPrev, prev, nextBlock);
                return;
            }

            // No ... so check the difficulty didn't actually change.
            if (nextBlock.getDifficultyTarget() != prev.getDifficultyTarget())
                throw new VerificationException("Unexpected change in difficulty at height " + storedPrev.getHeight() +
                        ": " + Long.toHexString(nextBlock.getDifficultyTarget()) + " vs " +
                        Long.toHexString(prev.getDifficultyTarget()));
            return;
        }

        // We need to find a block far back in the chain. It's OK that this is expensive because it only occurs every
        // two weeks after the initial block chain download.
        long now = System.currentTimeMillis();
        StoredBlock cursor = blockStore.get(prev.getHash());
        for (int i = 0; i < params.interval - 1; i++) {
            if (cursor == null) {
                // This should never happen. If it does, it means we are following an incorrect or busted chain.
                throw new VerificationException(
                        "Difficulty transition point but we did not find a way back to the genesis block.");
            }
            cursor = blockStore.get(cursor.getHeader().getPrevBlockHash());
        }
        log.info("Difficulty transition traversal took {}msec", System.currentTimeMillis() - now);

        Block blockIntervalAgo = cursor.getHeader();
        int timespan = (int) (prev.getTimeSeconds() - blockIntervalAgo.getTimeSeconds());
        // Limit the adjustment step.
        if (timespan < params.targetTimespan / 4)
            timespan = params.targetTimespan / 4;
        if (timespan > params.targetTimespan * 4)
            timespan = params.targetTimespan * 4;

        BigInteger newDifficulty = Utils.decodeCompactBits(prev.getDifficultyTarget());
        newDifficulty = newDifficulty.multiply(BigInteger.valueOf(timespan));
        newDifficulty = newDifficulty.divide(BigInteger.valueOf(params.targetTimespan));

        if (newDifficulty.compareTo(params.proofOfWorkLimit) > 0) {
            log.info("Difficulty hit proof of work limit: {}", newDifficulty.toString(16));
            newDifficulty = params.proofOfWorkLimit;
        }

        int accuracyBytes = (int) (nextBlock.getDifficultyTarget() >>> 24) - 3;
        BigInteger receivedDifficulty = nextBlock.getDifficultyTargetAsInteger();

        // The calculated difficulty is to a higher precision than received, so reduce here.
        BigInteger mask = BigInteger.valueOf(0xFFFFFFL).shiftLeft(accuracyBytes * 8);
        newDifficulty = newDifficulty.and(mask);

        if (newDifficulty.compareTo(receivedDifficulty) != 0)
            throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                    receivedDifficulty.toString(16) + " vs " + newDifficulty.toString(16));
    }

    private void checkTestnetDifficulty(StoredBlock storedPrev, Block prev, Block next) throws VerificationException, BlockStoreException {
        // After 15th February 2012 the rules on the testnet change to avoid people running up the difficulty
        // and then leaving, making it too hard to mine a block. On non-difficulty transition points, easy
        // blocks are allowed if there has been a span of 20 minutes without one.
        final long timeDelta = next.getTimeSeconds() - prev.getTimeSeconds();
        // There is an integer underflow bug in bitcoin-qt that means mindiff blocks are accepted when time
        // goes backwards.
        if (timeDelta >= 0 && timeDelta <= NetworkParameters.TARGET_SPACING * 2) {
            // Walk backwards until we find a block that doesn't have the easiest proof of work, then check
            // that difficulty is equal to that one.
            StoredBlock cursor = storedPrev;
            while (!cursor.getHeader().equals(params.genesisBlock) &&
                   cursor.getHeight() % params.interval != 0 &&
                   cursor.getHeader().getDifficultyTargetAsInteger().equals(params.proofOfWorkLimit))
                cursor = cursor.getPrev(blockStore);
            BigInteger cursorDifficulty = cursor.getHeader().getDifficultyTargetAsInteger();
            BigInteger newDifficulty = next.getDifficultyTargetAsInteger();
            if (!cursorDifficulty.equals(newDifficulty))
                throw new VerificationException("Testnet block transition that is not allowed: " +
                    Long.toHexString(cursor.getHeader().getDifficultyTarget()) + " vs " +
                    Long.toHexString(next.getDifficultyTarget()));
        }
    }

    /**
     * Returns true if any connected wallet considers any transaction in the block to be relevant.
     */
    private boolean containsRelevantTransactions(Block block) {
        for (Transaction tx : block.transactions) {
            try {
                for (BlockChainListener listener : listeners) {
                    if (listener.isTransactionRelevant(tx)) return true;
                }
            } catch (ScriptException e) {
                // We don't want scripts we don't understand to break the block chain so just note that this tx was
                // not scanned here and continue.
                log.warn("Failed to parse a script: " + e.toString());
            }
        }
        return false;
    }

    /**
     * Returns the block at the head of the current best chain. This is the block which represents the greatest
     * amount of cumulative work done.
     */
    public StoredBlock getChainHead() {
        synchronized (chainHeadLock) {
            return chainHead;
        }
    }

    /**
     * An orphan block is one that does not connect to the chain anywhere (ie we can't find its parent, therefore
     * it's an orphan). Typically this occurs when we are downloading the chain and didn't reach the head yet, and/or
     * if a block is solved whilst we are downloading. It's possible that we see a small amount of orphan blocks which
     * chain together, this method tries walking backwards through the known orphan blocks to find the bottom-most.
     *
     * @return from or one of froms parents, or null if "from" does not identify an orphan block
     */
    public synchronized Block getOrphanRoot(Sha256Hash from) {
        OrphanBlock cursor = orphanBlocks.get(from);
        if (cursor == null)
            return null;
        OrphanBlock tmp;
        while ((tmp = orphanBlocks.get(cursor.block.getPrevBlockHash())) != null) {
            cursor = tmp;
        }
        return cursor.block;
    }

    /** Returns true if the given block is currently in the orphan blocks list. */
    public synchronized boolean isOrphan(Sha256Hash block) {
        return orphanBlocks.containsKey(block);
    }

    /**
     * Returns an estimate of when the given block will be reached, assuming a perfect 10 minute average for each
     * block. This is useful for turning transaction lock times into human readable times. Note that a height in
     * the past will still be estimated, even though the time of solving is actually known (we won't scan backwards
     * through the chain to obtain the right answer).
     */
    public Date estimateBlockTime(int height) {
        synchronized (chainHeadLock) {
            long offset = height - chainHead.getHeight();
            long headTime = chainHead.getHeader().getTimeSeconds();
            long estimated = (headTime * 1000) + (1000L * 60L * 10L * offset);
            return new Date(estimated);
        }
    }
}
