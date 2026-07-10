package com.fintrack.transaction.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BatchTransactionRequest(
        @NotEmpty @Size(max = 100) List<CreateTransactionRequest> transactions
) {}
