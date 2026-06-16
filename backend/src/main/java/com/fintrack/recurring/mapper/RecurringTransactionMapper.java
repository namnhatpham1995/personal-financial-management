package com.fintrack.recurring.mapper;

import com.fintrack.recurring.domain.RecurringTransaction;
import com.fintrack.recurring.web.dto.RecurringResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RecurringTransactionMapper {

    @Mapping(target = "accountId", source = "account.id")
    @Mapping(target = "accountName", source = "account.name")
    @Mapping(target = "categoryId", source = "category.id")
    @Mapping(target = "categoryName", source = "category.name")
    RecurringResponse toResponse(RecurringTransaction entity);
}
