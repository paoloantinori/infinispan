package org.infinispan.statetransfer;

import java.util.concurrent.CompletableFuture;

import org.infinispan.commands.FlagAffectedCommand;
import org.infinispan.commands.TopologyAffectedCommand;
import org.infinispan.commands.VisitableCommand;
import org.infinispan.commands.control.LockControlCommand;
import org.infinispan.commands.functional.ReadOnlyKeyCommand;
import org.infinispan.commands.functional.ReadOnlyManyCommand;
import org.infinispan.commands.functional.ReadWriteKeyCommand;
import org.infinispan.commands.functional.ReadWriteKeyValueCommand;
import org.infinispan.commands.read.GetAllCommand;
import org.infinispan.commands.read.GetCacheEntryCommand;
import org.infinispan.commands.read.GetKeyValueCommand;
import org.infinispan.commands.tx.CommitCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.commands.tx.RollbackCommand;
import org.infinispan.commands.tx.TransactionBoundaryCommand;
import org.infinispan.commands.write.ApplyDeltaCommand;
import org.infinispan.commands.write.ClearCommand;
import org.infinispan.commands.write.EvictCommand;
import org.infinispan.commands.write.InvalidateCommand;
import org.infinispan.commands.write.InvalidateL1Command;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.commands.write.PutMapCommand;
import org.infinispan.commands.write.RemoveCommand;
import org.infinispan.commands.write.ReplaceCommand;
import org.infinispan.commands.write.WriteCommand;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.FlagBitSets;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.interceptors.InvocationFinallyFunction;
import org.infinispan.interceptors.impl.BaseStateTransferInterceptor;
import org.infinispan.remoting.RemoteException;
import org.infinispan.remoting.responses.UnsureResponse;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.jgroups.SuspectException;
import org.infinispan.topology.CacheTopology;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

//todo [anistor] command forwarding breaks the rule that we have only one originator for a command. this opens now the possibility to have two threads processing incoming remote commands for the same TX
/**
 * This interceptor has two tasks:
 * <ol>
 *    <li>If the command's topology id is higher than the current topology id,
 *    wait for the node to receive transaction data for the new topology id.</li>
 *    <li>If the topology id changed during a command's execution, retry the command, but only on the
 *    originator (which replicates it to the new owners).</li>
 * </ol>
 *
 * If the cache is configured with asynchronous replication, owners cannot signal to the originator that they
 * saw a new topology, so instead each owner forwards the command to all the other owners in the new topology.
 *
 * @author anistor@redhat.com
 */
public class StateTransferInterceptor extends BaseStateTransferInterceptor {

   private static final Log log = LogFactory.getLog(StateTransferInterceptor.class);
   private static final boolean trace = log.isTraceEnabled();

   private StateTransferManager stateTransferManager;

   private final InvocationFinallyFunction handleReadCommandReturn = this::handleReadCommandReturn;
   private final InvocationFinallyFunction handleTxReturn = this::handleTxReturn;
   private final InvocationFinallyFunction handleTxWriteReturn = this::handleTxWriteReturn;
   private final InvocationFinallyFunction handleNonTxWriteReturn = this::handleNonTxWriteReturn;

   @Inject
   public void init(StateTransferManager stateTransferManager) {
      this.stateTransferManager = stateTransferManager;
   }

   @Override
   public Object visitPrepareCommand(TxInvocationContext ctx, PrepareCommand command)
         throws Throwable {
      return handleTxCommand(ctx, command);
   }

   @Override
   public Object visitCommitCommand(TxInvocationContext ctx, CommitCommand command)
         throws Throwable {
      return handleTxCommand(ctx, command);
   }

   @Override
   public Object visitRollbackCommand(TxInvocationContext ctx, RollbackCommand command)
         throws Throwable {
      return handleTxCommand(ctx, command);
   }

   @Override
   public Object visitLockControlCommand(TxInvocationContext ctx, LockControlCommand command)
         throws Throwable {
      if (trace) log.tracef("handleTxCommand for command %s, origin %s", command, getOrigin(ctx));

      if (isLocalOnly(command)) {
         return invokeNext(ctx, command);
      }
      updateTopologyId(command);

      return invokeNextAndHandle(ctx, command, handleTxReturn);
   }

   @Override
   public Object visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command)
         throws Throwable {
      return handleWriteCommand(ctx, command);
   }

   @Override
   public Object visitPutMapCommand(InvocationContext ctx, PutMapCommand command)
         throws Throwable {
      return handleWriteCommand(ctx, command);
   }

   @Override
   public Object visitApplyDeltaCommand(InvocationContext ctx, ApplyDeltaCommand command)
         throws Throwable {
      return handleWriteCommand(ctx, command);
   }

   @Override
   public Object visitRemoveCommand(InvocationContext ctx, RemoveCommand command)
         throws Throwable {
      return handleWriteCommand(ctx, command);
   }

   @Override
   public Object visitReplaceCommand(InvocationContext ctx, ReplaceCommand command)
         throws Throwable {
      return handleWriteCommand(ctx, command);
   }

   @Override
   public Object visitClearCommand(InvocationContext ctx, ClearCommand command)
         throws Throwable {
      return handleWriteCommand(ctx, command);
   }

   @Override
   public Object visitInvalidateCommand(InvocationContext ctx, InvalidateCommand command)
         throws Throwable {
      return handleWriteCommand(ctx, command);
   }

   @Override
   public Object visitInvalidateL1Command(InvocationContext ctx, InvalidateL1Command command)
         throws Throwable {
      // no need to forward this command
      return invokeNext(ctx, command);
   }

   @Override
   public Object visitEvictCommand(InvocationContext ctx, EvictCommand command)
         throws Throwable {
      // it's not necessary to propagate eviction to the new owners in case of state transfer
      return invokeNext(ctx, command);
   }

   @Override
   public Object visitGetKeyValueCommand(InvocationContext ctx, GetKeyValueCommand command) throws Throwable {
      return handleReadCommand(ctx, command);
   }

   @Override
   public Object visitGetCacheEntryCommand(InvocationContext ctx, GetCacheEntryCommand command)
         throws Throwable {
      return handleReadCommand(ctx, command);
   }

   @Override
   public Object visitGetAllCommand(InvocationContext ctx, GetAllCommand command) throws Throwable {
      return handleReadCommand(ctx, command);
   }

   private <C extends VisitableCommand & TopologyAffectedCommand & FlagAffectedCommand> Object handleReadCommand(InvocationContext ctx, C command) throws Throwable {
      return isLocalOnly(command) ? invokeNext(ctx, command) :
            updateAndInvokeNextRead(ctx, command);
   }

   private <C extends VisitableCommand & TopologyAffectedCommand> Object updateAndInvokeNextRead(InvocationContext ctx, C command)
         throws InterruptedException {
      updateTopologyId(command);
      return invokeNextAndHandle(ctx, command,handleReadCommandReturn);
   }

   private Object handleReadCommandReturn(InvocationContext rCtx, VisitableCommand rCommand, Object rv, Throwable t)
         throws Throwable {
      if (t == null)
         return rv;

      Throwable ce = t;
      while (ce instanceof RemoteException) {
         ce = ce.getCause();
      }
      final CacheTopology cacheTopology = stateTransferManager.getCacheTopology();
      int currentTopologyId = cacheTopology == null ? -1 : cacheTopology.getTopologyId();
      TopologyAffectedCommand cmd = (TopologyAffectedCommand) rCommand;
      if (ce instanceof SuspectException) {
         if (trace)
            log.tracef("Retrying command because of suspected node, current topology is %d: %s",
                  currentTopologyId, rCommand);
         // It is possible that current topology is actual but the view still contains a node that's about to leave;
         // a broadcast to all nodes then can end with suspect exception, but we won't get any new topology.
         // An example of this situation is when a node sends leave - topology can be installed before the new view.
         // To prevent suspect exceptions use SYNCHRONOUS_IGNORE_LEAVERS response mode.
         if (currentTopologyId == cmd.getTopologyId() && !cacheTopology.getActualMembers().contains(((SuspectException) ce).getSuspect())) {
            // TODO: provide a test case
            throw new IllegalStateException("Command was not sent with SYNCHRONOUS_IGNORE_LEAVERS?");
         }
      } else if (ce instanceof OutdatedTopologyException) {
         if (trace)
            log.tracef("Retrying command because of topology change, current topology is %d: %s",
                  currentTopologyId, cmd);
      } else {
         throw t;
      }
      // We increment the topology to wait for the next topology.
      // Without this, we could retry the command too fast and we could get the OutdatedTopologyException again.
      int newTopologyId = getNewTopologyId(ce, currentTopologyId, cmd);
      cmd.setTopologyId(newTopologyId);
      ((FlagAffectedCommand)rCommand).addFlags(FlagBitSets.COMMAND_RETRY);
      CompletableFuture<Void> topologyFuture = stateTransferLock.topologyFuture(newTopologyId);
      return retryWhenDone(topologyFuture, newTopologyId, rCtx, rCommand, handleReadCommandReturn);
   }

   @Override
   public Object visitReadWriteKeyValueCommand(InvocationContext ctx,
                                               ReadWriteKeyValueCommand command) throws Throwable {
      return handleWriteCommand(ctx, command);
   }

   @Override
   public Object visitReadWriteKeyCommand(InvocationContext ctx, ReadWriteKeyCommand command)
         throws Throwable {
      return handleWriteCommand(ctx, command);
   }

   @Override
   public Object visitReadOnlyKeyCommand(InvocationContext ctx, ReadOnlyKeyCommand command) throws Throwable {
      return handleReadCommand(ctx, command);
   }

   @Override
   public Object visitReadOnlyManyCommand(InvocationContext ctx, ReadOnlyManyCommand command) throws Throwable {
      return handleReadCommand(ctx, command);
   }

   /**
    * Special processing required for transaction commands.
    *
    */
   private Object handleTxCommand(TxInvocationContext ctx, TransactionBoundaryCommand command) throws Throwable {
      if (trace) log.tracef("handleTxCommand for command %s, origin %s", command, getOrigin(ctx));
      updateTopologyId(command);

      return invokeNextAndHandle(ctx, command, handleTxReturn);
   }

   private Address getOrigin(TxInvocationContext ctx) {
      // For local commands we may not have a GlobalTransaction yet
      return ctx.isOriginLocal() ? ctx.getOrigin() : ctx.getGlobalTransaction().getAddress();
   }

   private Object handleTxReturn(InvocationContext ctx,
                                 VisitableCommand command, Object rv, Throwable t) throws Throwable {
      TransactionBoundaryCommand txCommand = (TransactionBoundaryCommand) command;

      int retryTopologyId = -1;
      int currentTopology = currentTopologyId();
      if (t instanceof OutdatedTopologyException) {
         // This can only happen on the originator
         retryTopologyId = Math.max(currentTopology, txCommand.getTopologyId() + 1);
      } else if (t != null) {
         throw t;
      }

      if (ctx.isOriginLocal()) {
         // On the originator, we only retry if we got an OutdatedTopologyException
         // Which could be caused either by an owner leaving or by an owner having a newer topology
         // No need to retry just because we have a new topology on the originator, all entries were
         // wrapped anyway

         if (retryTopologyId > 0) {
            // Only the originator can retry the command
            txCommand.setTopologyId(retryTopologyId);
            if (txCommand instanceof PrepareCommand) {
               ((PrepareCommand) txCommand).setRetriedCommand(true);
            }
            CompletableFuture<Void> transactionDataFuture = stateTransferLock.transactionDataFuture(retryTopologyId);
            return retryWhenDone(transactionDataFuture, retryTopologyId, ctx, txCommand, handleTxReturn);
         }
      } else {
         if (currentTopology > txCommand.getTopologyId()) {
            // Signal the originator to retry
            return UnsureResponse.INSTANCE;
         }
      }
      return rv;
   }

   protected Object handleWriteCommand(InvocationContext ctx, WriteCommand command)
         throws Throwable {
      if (ctx.isInTxScope()) {
         return handleTxWriteCommand(ctx, command);
      } else {
         return handleNonTxWriteCommand(ctx, command);
      }
   }

   private Object handleTxWriteCommand(InvocationContext ctx, WriteCommand command)
         throws Throwable {
      if (trace) log.tracef("handleTxWriteCommand for command %s, origin %s", command, ctx.getOrigin());

      if (isLocalOnly(command)) {
         return invokeNext(ctx, command);
      }
      updateTopologyId(command);

      return invokeNextAndHandle(ctx, command, handleTxWriteReturn);
   }

   private Object handleTxWriteReturn(InvocationContext rCtx, VisitableCommand rCommand, Object rv, Throwable t)
         throws Throwable {
      int retryTopologyId = -1;
      WriteCommand writeCommand = (WriteCommand) rCommand;
      if (t instanceof OutdatedTopologyException) {
         // This can only happen on the originator
         retryTopologyId = Math.max(currentTopologyId(), writeCommand.getTopologyId() + 1);
      } else if (t != null) {
         throw t;
      }

      if (rCtx.isOriginLocal()) {
         // On the originator, we only retry if we got an OutdatedTopologyException
         // Which could be caused either by an owner leaving or by an owner having a newer topology
         // No need to retry just because we have a new topology on the originator, all entries were
         // wrapped anyway
         if (retryTopologyId > 0) {
            // Only the originator can retry the command
            writeCommand.setTopologyId(retryTopologyId);
            CompletableFuture<Void> transactionDataFuture = stateTransferLock.transactionDataFuture(retryTopologyId);
            return retryWhenDone(transactionDataFuture, retryTopologyId, rCtx, writeCommand, handleTxWriteReturn);
         }
      } else {
         if (currentTopologyId() > writeCommand.getTopologyId()) {
            // Signal the originator to retry
            return UnsureResponse.INSTANCE;
         }
      }
      return rv;
   }

   /**
    * For non-tx write commands, we retry the command locally if the topology changed.
    * But we only retry on the originator, and only if the command doesn't have
    * the {@code CACHE_MODE_LOCAL} flag.
    */
   private Object handleNonTxWriteCommand(InvocationContext ctx, WriteCommand command)
         throws Throwable {
      if (trace) log.tracef("handleNonTxWriteCommand for command %s, topology id %d", command, command.getTopologyId());

      if (isLocalOnly(command)) {
         return invokeNext(ctx, command);
      }

      updateTopologyId(command);

      // Only catch OutdatedTopologyExceptions on the originator
      if (!ctx.isOriginLocal()) {
         return invokeNext(ctx, command);
      }

      return invokeNextAndHandle(ctx, command, handleNonTxWriteReturn);
   }

   private Object handleNonTxWriteReturn(InvocationContext rCtx,
                                         VisitableCommand rCommand, Object rv, Throwable t) throws Throwable {
      if (t == null)
         return rv;

      Throwable ce = t;
      while (ce instanceof RemoteException) {
         ce = ce.getCause();
      }
      if (!(ce instanceof OutdatedTopologyException) && !(ce instanceof SuspectException))
         throw t;

      // We increment the topology id so that updateTopologyIdAndWaitForTransactionData waits for the
      // next topology.
      // Without this, we could retry the command too fast and we could get the
      // OutdatedTopologyException again.
      int currentTopologyId = currentTopologyId();
      WriteCommand writeCommand = (WriteCommand) rCommand;
      int newTopologyId = getNewTopologyId(ce, currentTopologyId, writeCommand);
      if (trace)
         log.tracef("Retrying command because of topology change, current topology is %d (requested: %d): %s",
               currentTopologyId, newTopologyId, writeCommand);
      writeCommand.setTopologyId(newTopologyId);
      writeCommand.addFlags(FlagBitSets.COMMAND_RETRY);
      // In non-tx context, waiting for transaction data is equal to waiting for topology
      CompletableFuture<Void> transactionDataFuture = stateTransferLock.transactionDataFuture(newTopologyId);
      return retryWhenDone(transactionDataFuture, newTopologyId, rCtx, writeCommand, handleNonTxWriteReturn);
   }

   private int getNewTopologyId(Throwable ce, int currentTopologyId, TopologyAffectedCommand command) {
      int requestedTopologyId = command.getTopologyId() + 1;
      if (ce instanceof OutdatedTopologyException) {
         OutdatedTopologyException ote = (OutdatedTopologyException) ce;
         if (ote.requestedTopologyId >= 0) {
            requestedTopologyId = ote.requestedTopologyId;
         }
      }
      return Math.max(currentTopologyId, requestedTopologyId);
   }

   @Override
   public Object handleDefault(InvocationContext ctx, VisitableCommand command)
         throws Throwable {
      if (command instanceof TopologyAffectedCommand) {
         return handleTopologyAffectedCommand(ctx, command, ctx.getOrigin());
      } else {
         return invokeNext(ctx, command);
      }
   }

   private Object handleTopologyAffectedCommand(InvocationContext ctx,
                                                VisitableCommand command, Address origin) throws Throwable {
      if (trace) log.tracef("handleTopologyAffectedCommand for command %s, origin %s", command, origin);

      if (isLocalOnly((FlagAffectedCommand) command)) {
         return invokeNext(ctx, command);
      }
      updateTopologyId((TopologyAffectedCommand) command);

      return invokeNext(ctx, command);
   }

   private boolean isLocalOnly(FlagAffectedCommand command) {
      return command.hasAnyFlag(FlagBitSets.CACHE_MODE_LOCAL);
   }

   @Override
   protected Log getLog() {
      return log;
   }
}
