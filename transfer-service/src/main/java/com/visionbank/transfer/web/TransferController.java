package com.visionbank.transfer.web;

import com.visionbank.transfer.domain.Transfer;
import com.visionbank.transfer.repository.TransferRepository;
import com.visionbank.transfer.service.SubmitTransferCommand;
import com.visionbank.transfer.service.TransferSubmissionService;
import com.visionbank.transfer.service.TransferView;
import com.visionbank.transfer.web.dto.SubmitTransferDto;
import com.visionbank.transfer.web.dto.TransferResponseDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
    public TransferResponseDto get(@PathVariable String id) {
        Transfer t = transfers.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No transfer " + id));
        return new TransferResponseDto(t.getTransferId(), t.getState());
    }
}
