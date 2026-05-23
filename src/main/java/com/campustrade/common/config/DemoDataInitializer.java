package com.campustrade.common.config;

import com.campustrade.auth.security.PasswordHasher;
import com.campustrade.item.model.ConditionLevel;
import com.campustrade.item.model.Item;
import com.campustrade.item.model.ItemStatus;
import com.campustrade.item.repository.ItemRepository;
import com.campustrade.user.model.User;
import com.campustrade.user.model.UserRole;
import com.campustrade.user.model.UserStatus;
import com.campustrade.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class DemoDataInitializer implements CommandLineRunner {

    private final boolean seedData;
    private final UserRepository userRepository;
    private final ItemRepository itemRepository;
    private final PasswordHasher passwordHasher;

    public DemoDataInitializer(
            @Value("${app.demo.seed-data:true}") boolean seedData,
            UserRepository userRepository,
            ItemRepository itemRepository,
            PasswordHasher passwordHasher
    ) {
        this.seedData = seedData;
        this.userRepository = userRepository;
        this.itemRepository = itemRepository;
        this.passwordHasher = passwordHasher;
    }

    @Override
    public void run(String... args) {
        if (!seedData || userRepository.findByUsername("seller").isPresent()) {
            return;
        }
        User seller = createUser("seller", "卖家同学", UserRole.USER);
        createUser("buyer", "买家同学", UserRole.USER);
        createUser("admin", "管理员", UserRole.ADMIN);

        createItem(seller.getId(), "iPad Air 5 64G", "自用 iPad，屏幕无划痕，适合记笔记和看网课。", "电子产品",
                new BigDecimal("2599.00"), ConditionLevel.LIKE_NEW, "东校区", "图书馆一楼");
        createItem(seller.getId(), "数据结构教材 + 习题册", "课程刚结束，书内少量笔记，适合期末复习。", "书籍教材",
                new BigDecimal("39.90"), ConditionLevel.GOOD, "东校区", "教学楼 A 座");
    }

    private User createUser(String username, String nickname, UserRole role) {
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordHasher.hash("123456"));
        user.setNickname(nickname);
        user.setPhone("1880000" + username.length() + Math.abs(username.hashCode() % 1000));
        user.setCampus("东校区");
        user.setRole(role);
        user.setStatus(UserStatus.NORMAL);
        user.setCreditScore(100);
        return userRepository.save(user);
    }

    private void createItem(
            Long sellerId,
            String title,
            String description,
            String category,
            BigDecimal price,
            ConditionLevel conditionLevel,
            String campus,
            String tradePlace
    ) {
        Item item = new Item();
        item.setSellerId(sellerId);
        item.setTitle(title);
        item.setDescription(description);
        item.setCategory(category);
        item.setPrice(price);
        item.setConditionLevel(conditionLevel);
        item.setCampus(campus);
        item.setTradePlace(tradePlace);
        item.setStatus(ItemStatus.ON_SALE);
        item.setImageUrls(List.of("https://example.com/demo/" + title.hashCode() + ".jpg"));
        itemRepository.save(item);
    }
}
