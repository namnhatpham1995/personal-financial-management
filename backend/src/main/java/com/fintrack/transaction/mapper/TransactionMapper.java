package com.fintrack.transaction.mapper;

import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.web.dto.TransactionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TransactionMapper {

    @Mapping(target = "accountId", source = "account.id")
    @Mapping(target = "accountName", source = "account.name")
    @Mapping(target = "currency", source = "account.currency")
    @Mapping(target = "destinationCurrency", source = "transferAccount.currency")
    @Mapping(target = "transferAccountId", source = "transferAccount.id")
    @Mapping(target = "transferAccountName", source = "transferAccount.name")
    @Mapping(target = "categoryId", source = "category.id")
    @Mapping(target = "categoryName", source = "category.name")
    @Mapping(target = "warnings", ignore = true)
    TransactionResponse toResponse(Transaction transaction);
}
