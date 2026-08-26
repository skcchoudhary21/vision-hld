package com.visionbank.approval.web;

import com.visionbank.approval.policy.PolicyRuleNotFoundException;
import com.visionbank.approval.service.*;
import com.visionbank.approval.web.dto.ErrorResponseDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ConcurrentStateChangeException.class)
    public ResponseEntity<ErrorResponseDto> handle(ConcurrentStateChangeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponseDto("CONCURRENT_STATE_CHANGE", e.requestId, e.currentState, null));
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ErrorResponseDto> handle(InvalidStateTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponseDto("INVALID_STATE_TRANSITION", e.requestId, e.currentState, e.requestedAction));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponseDto> handle(IdempotencyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponseDto("IDEMPOTENCY_CONFLICT", null, null, null));
    }

    @ExceptionHandler(ForbiddenActionException.class)
    public ResponseEntity<ErrorResponseDto> handle(ForbiddenActionException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponseDto("FORBIDDEN_ACTION", null, null, null));
    }

    @ExceptionHandler(ApprovalRequestNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handle(ApprovalRequestNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponseDto("NOT_FOUND", null, null, null));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponseDto> handle(InvalidRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponseDto("INVALID_REQUEST", null, null, null));
    }

    @ExceptionHandler(WorkflowNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handle(WorkflowNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponseDto("WORKFLOW_NOT_FOUND", null, null, null));
    }

    @ExceptionHandler(PolicyRuleNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handle(PolicyRuleNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponseDto("POLICY_RULE_NOT_FOUND", null, null, null));
    }
}
