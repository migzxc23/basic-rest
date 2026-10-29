package com.miguelrecido.basicrest.service;

import com.miguelrecido.basicrest.exception.ItemNotFoundException;
import com.miguelrecido.basicrest.model.Item;
import com.miguelrecido.basicrest.model.ItemRequest;
import com.miguelrecido.basicrest.repository.ItemRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ItemService {

    private final ItemRepository repository;

    public ItemService(ItemRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<Item> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Item findById(Long id) {
        return repository.findById(id).orElseThrow(() -> new ItemNotFoundException(id));
    }

    public Item create(ItemRequest req) {
        Item item = new Item();
        apply(item, req);
        return repository.save(item);
    }

    public Item update(Long id, ItemRequest req) {
        Item item = findById(id);
        apply(item, req);
        return repository.save(item);
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ItemNotFoundException(id);
        }
        repository.deleteById(id);
    }

    private static void apply(Item item, ItemRequest req) {
        item.setName(req.name());
        item.setDescription(req.description());
        item.setPrice(req.price());
    }
}
