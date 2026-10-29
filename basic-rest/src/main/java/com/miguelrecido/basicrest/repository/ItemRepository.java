package com.miguelrecido.basicrest.repository;

import com.miguelrecido.basicrest.model.Item;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, Long> {
}
