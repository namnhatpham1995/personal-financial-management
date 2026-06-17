package com.fintrack.account.mapper;

import com.fintrack.account.domain.Account;
import com.fintrack.account.web.dto.AccountResponse;
import com.fintrack.account.web.dto.CreateAccountRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface AccountMapper {

    AccountResponse toResponse(Account account);

    List<AccountResponse> toResponseList(List<Account> accounts);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "currentBalance", source = "initialBalance")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Account toEntity(CreateAccountRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "currentBalance", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntity(com.fintrack.account.web.dto.UpdateAccountRequest request, @MappingTarget Account account);
}
