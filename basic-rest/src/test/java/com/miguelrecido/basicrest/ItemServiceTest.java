package com.miguelrecido.basicrest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.miguelrecido.basicrest.exception.ItemNotFoundException;
import com.miguelrecido.basicrest.model.Item;
import com.miguelrecido.basicrest.model.ItemRequest;
import com.miguelrecido.basicrest.repository.ItemRepository;
import com.miguelrecido.basicrest.service.ItemService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ItemServiceTest {

    private final ItemRepository repo = mock(ItemRepository.class);
    private final ItemService service = new ItemService(repo);

    @Test
    void findByIdThrowsWhenMissing() {
        when(repo.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findById(1L)).isInstanceOf(ItemNotFoundException.class);
    }

    @Test
    void createMapsRequestToEntity() {
        when(repo.save(org.mockito.ArgumentMatchers.any(Item.class))).thenAnswer(i -> i.getArgument(0));
        Item saved = service.create(new ItemRequest("pen", "blue", new BigDecimal("1.50")));
        assertThat(saved.getName()).isEqualTo("pen");
        assertThat(saved.getPrice()).isEqualByComparingTo("1.50");
    }
}
