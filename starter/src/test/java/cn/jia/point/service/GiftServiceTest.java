package cn.jia.point.service;

import cn.jia.point.entity.PointGiftEntity;
import cn.jia.test.BaseDbUnitTest;
import cn.jia.test.DbUnitHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.test.context.jdbc.Sql;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class GiftServiceTest extends BaseDbUnitTest {
    @Autowired
    private GiftService giftService;
    @Autowired
    private DataSource dataSource;
    @Value("classpath:testObject/point/point_gift_init.json")
    private Resource resource;

    @Test
    void create() {
        PointGiftEntity gift = DbUnitHelper.readJsonEntity(resource, PointGiftEntity.class);
        gift = giftService.create(gift);
        assertNotNull(gift);
    }

    @Test
    @Sql(scripts = "classpath:testObject/point/point_gift_init.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
    void find() throws Exception {
        PointGiftEntity gift = giftService.find(1L);
        assertNotNull(gift);
    }

    @Test
    void update() {
        PointGiftEntity gift = new PointGiftEntity();
        gift.setId(1L);
        gift.setName("testName");
        giftService.update(gift);
        assertTrue(true);
    }

    @Test
    void delete() {
        giftService.delete(1L);
        assertTrue(true);
    }

    @Test
    @Sql(scripts = "classpath:testObject/point/point_gift_init.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
    void list() {
        PageInfo<PointGiftEntity> giftPage = giftService.list(1, 10, null, null);
        assertNotEquals(giftPage.getList().size(), 0);
    }

    @Test
    void usage() {
    }

    @Test
    void usageCancel() {
    }

    @Test
    void usageDelete() {
    }

    @Test
    void usageListByGift() {
    }

    @Test
    void usageListByUser() {
    }

    @Test
    @Disabled
    void printDataSet() {
        log.info(DbUnitHelper.printDataSet(dataSource, "point_gift", "select * from point_gift where id = 1"));
    }
}