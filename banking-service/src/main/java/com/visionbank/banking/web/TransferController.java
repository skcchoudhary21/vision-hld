package com.visionbank.banking.web;

import com.visionbank.banking.domain.Transfer;
import com.visionbank.banking.repository.TransferRepository;
import com.visionbank.banking.service.SubmitTransferCommand;
import com.visionbank.banking.service.TransferSubmissionService;
import com.visionbank.banking.service.TransferView;
import com.visionbank.banking.web.dto.SubmitTransferDto;
import com.visionbank.banking.web.dto.TransferDetailDto;
import com.visionbank.banking.web.dto.TransferResponseDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferSubmissionService submissionService;
    private final TransferRepository transfers;

    public TransferController(TransferSubmissionService submissionService, TransferRepository transfers) {
        this.submissionService = submissionService;
        this.transfers = transfers;
    }

    @PostMapping
    public TransferResponseDto submit(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @Valid @RequestBody SubmitTransferDto dto) {
        SubmitTransferCommand cmd = new SubmitTransferCommand(dto.makerId(), dto.fromAccount(), dto.toAccount(),
                dto.amountMinorUnits(), dto.currency());
        TransferView view = submissionService.submit(cmd, idempotencyKey);
        return new TransferResponseDto(view.transferId(), view.state());
    }

    @GetMapping("/{id}")
    public TransferDetailDto get(@PathVariable String id) {
        Transfer t = transfers.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No transfer " + id));
        return toDetail(t);
    }

    // Two lookup shapes on one endpoint: by maker (My Account's own request
    // list) or by an explicit id batch (the Approval Workspace resolving
    // amounts for a page of unrelated requests in one round trip instead of
    // one call per row).
    @GetMapping
    public List<TransferDetailDto> list(@RequestParam(required = false) String makerId,
                                         @RequestParam(required = false) List<String> ids) {
        if (ids != null) {
            return transfers.findAllById(ids).stream().map(this::toDetail).toList();
        }
        return transfers.findByMakerIdOrderByCreatedAtDesc(makerId).stream()
                .map(this::toDetail)
                .toList();
    }

    private TransferDetailDto toDetail(Transfer t) {
        return new TransferDetailDto(t.getTransferId(), t.getMakerId(), t.getFromAccount(), t.getToAccount(),
                t.getAmountMinorUnits(), t.getCurrency(), t.getState(), t.getApprovalRequestId(), t.getCreatedAt());
    }
}
