package com.fintrack.transaction.controller.dto;

import java.util.List;

public record BatchTransactionResponse(List<BatchTransactionRowResponse> results) {}
