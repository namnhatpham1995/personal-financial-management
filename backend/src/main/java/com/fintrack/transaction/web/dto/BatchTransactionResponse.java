package com.fintrack.transaction.web.dto;

import java.util.List;

public record BatchTransactionResponse(List<BatchTransactionRowResult> results) {}
