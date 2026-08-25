package com.visionbank.transfer.service;

import com.visionbank.transfer.corebanking.CoreBankingClient;
import com.visionbank.transfer.domain.Transfer;
import com.visionbank.transfer.domain.TransferState;
import com.visionbank.transfer.repository.TransferRepository;
import org.springframework.stereotype.Service;

@Service
public class ReleaseService {

    private final CoreBankingClient coreBanking;
    private final TransferRepository transfers;

    public ReleaseService(CoreBankingClient coreBanking, TransferRepository transfers) {
        this.coreBanking = coreBanking;
        this.transfers = transfers;
    }

    public void release(Transfer transfer) {
        if (transfer.getState() == TransferState.RELEASED) {
            return; // idempotent no-op — already released
        }
        transfer.setState(TransferState.RELEASE_PENDING);
        transfers.save(transfer);

        boolean success = coreBanking.release(transfer.getTransferId(), transfer.getFromAccount(), transfer.getAmountMinorUnits());
        if (success) {
            transfer.setState(TransferState.RELEASED);
            transfers.save(transfer);
        }
        // On failure the transfer stays RELEASE_PENDING; a retry scheduler polling
        // RELEASE_PENDING rows would call release() again — out of scope for this
        // exercise since the stub always succeeds, but the state exists for it.
    }
}
