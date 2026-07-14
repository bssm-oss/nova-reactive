package io.nova;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import io.nova.core.ReactiveEntityOperations;
import io.nova.schema.SchemaInitializer;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code @MapsId} 파생 식별자(shared primary key)가 실제 r2dbc-h2 driver 위에서 full round-trip 되는지
 * 검증한다 — master save 후 detail(@MapsId) save 시 detail.id가 master.id로 파생되어 INSERT되고,
 * findById로 복원되는지까지.
 *
 * <p>SQL string 단위 테스트만으로는 파생 식별자가 INSERT에 app-supplied로 실리는지(driver-key 회수 경로를
 * 타지 않는지), 존재확인 SELECT 기반 insert/update 분기가 driver에서 받아들여지는지를 검증할 수 없다. 이
 * 통합 테스트가 production {@link Nova} 배선으로 그 수용성을 고정한다.
 */
class MapsIdH2IntegrationTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private ConnectionFactory freshConnectionFactory() {
        int seq = DB_SEQ.incrementAndGet();
        return ConnectionFactories.get(
                "r2dbc:h2:mem:///mapsid" + seq + "?options=DB_CLOSE_DELAY=-1");
    }

    @Test
    void detailDerivesPrimaryKeyFromMasterAndRoundTrips() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        Master master = new Master("acme");

        StepVerifier.create(
                schema.create(Master.class)
                        .then(schema.create(Detail.class))
                        .then(operations.save(master))
                        .flatMap(savedMaster -> {
                            assertNotNull(savedMaster.getId(), "master는 IDENTITY로 id가 채워져야 한다");
                            Detail detail = new Detail("frankfurt");
                            detail.setMaster(savedMaster);
                            return operations.save(detail);
                        })
                        .flatMap(savedDetail -> {
                            // detail.id는 @MapsId로 master.id에서 파생된다.
                            assertEquals(master.getId(), savedDetail.getId(),
                                    "detail.id는 master.id로 파생되어야 한다");
                            return operations.findById(Detail.class, savedDetail.getId());
                        })
        ).assertNext(loaded -> {
            assertEquals(master.getId(), loaded.getId());
            assertEquals("frankfurt", loaded.getCity());
            assertNotNull(loaded.getMaster(), "@MapsId 관계는 findById에서 hydrate되어야 한다");
            assertEquals(master.getId(), loaded.getMaster().getId());
        }).verifyComplete();
    }

    @Test
    void secondSaveOfSameDerivedKeyTakesUpdatePath() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        Master master = new Master("globex");

        StepVerifier.create(
                schema.create(Master.class)
                        .then(schema.create(Detail.class))
                        .then(operations.save(master))
                        .flatMap(savedMaster -> {
                            Detail detail = new Detail("paris");
                            detail.setMaster(savedMaster);
                            return operations.save(detail)
                                    .flatMap(first -> {
                                        // 같은 파생키로 다시 save → 존재확인 SELECT가 row를 찾아 UPDATE 경로를 탄다.
                                        first.setCity("london");
                                        return operations.save(first);
                                    })
                                    .flatMap(updated -> operations.findById(Detail.class, updated.getId()));
                        })
        ).assertNext(loaded -> {
            assertEquals(master.getId(), loaded.getId());
            assertEquals("london", loaded.getCity(),
                    "두 번째 save는 INSERT 중복이 아니라 UPDATE로 처리되어야 한다");
        }).verifyComplete();
    }

    @Test
    void mapsIdComponentDerivesOneCompositeIdComponentFromParentAndRoundTrips() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        Company company = new Company("acme");

        StepVerifier.create(
                schema.create(Company.class)
                        .then(schema.create(Branch.class))
                        .then(operations.save(company))
                        .flatMap(savedCompany -> {
                            assertNotNull(savedCompany.getId(), "company는 IDENTITY로 id가 채워져야 한다");
                            Branch branch = new Branch(7L, "berlin");
                            branch.setCompany(savedCompany);
                            return operations.save(branch);
                        })
                        .flatMap(savedBranch -> {
                            // branch.id.companyRef는 @MapsId("companyRef")로 company.id에서 파생된다.
                            assertEquals(company.getId(), savedBranch.getCompanyRef(),
                                    "복합 @Id의 companyRef 컴포넌트가 company.id로 파생되어야 한다");
                            assertEquals(7L, savedBranch.getBranchNo());
                            BranchId key = new BranchId(company.getId(), 7L);
                            return operations.findById(Branch.class, key);
                        })
        ).assertNext(loaded -> {
            assertEquals(company.getId(), loaded.getCompanyRef());
            assertEquals(7L, loaded.getBranchNo());
            assertEquals("berlin", loaded.getCity());
            assertNotNull(loaded.getCompany(), "@MapsId 관계는 findById에서 hydrate되어야 한다");
            assertEquals(company.getId(), loaded.getCompany().getId());
        }).verifyComplete();
    }

    // --- fixtures -----------------------------------------------------------

    @Entity
    @Table(name = "maps_id_master")
    static class Master {
        @Id
        @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
        private Long id;

        @Column(name = "name")
        private String name;

        Master() {
        }

        Master(String name) {
            this.name = name;
        }

        Long getId() {
            return id;
        }

        String getName() {
            return name;
        }
    }

    @Entity
    @Table(name = "maps_id_detail")
    static class Detail {
        @Id
        private Long id;

        @Column(name = "city")
        private String city;

        @OneToOne
        @MapsId
        @JoinColumn(name = "master_id")
        private Master master;

        Detail() {
        }

        Detail(String city) {
            this.city = city;
        }

        Long getId() {
            return id;
        }

        String getCity() {
            return city;
        }

        void setCity(String city) {
            this.city = city;
        }

        Master getMaster() {
            return master;
        }

        void setMaster(Master master) {
            this.master = master;
        }
    }

    // --- composite-component @MapsId fixtures -------------------------------

    @Entity
    @Table(name = "maps_id_company")
    static class Company {
        @Id
        @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
        private Long id;

        @Column(name = "name")
        private String name;

        Company() {
        }

        Company(String name) {
            this.name = name;
        }

        Long getId() {
            return id;
        }
    }

    @Embeddable
    static class BranchId {
        @Column(name = "company_ref")
        private Long companyRef;

        @Column(name = "branch_no")
        private Long branchNo;

        BranchId() {
        }

        BranchId(Long companyRef, Long branchNo) {
            this.companyRef = companyRef;
            this.branchNo = branchNo;
        }
    }

    @Entity
    @Table(name = "maps_id_branch")
    static class Branch {
        @EmbeddedId
        private BranchId id;

        @Column(name = "city")
        private String city;

        // 복합 @Id의 companyRef 컴포넌트를 company.id에서 파생한다. FK 컬럼(company_id)과 컴포넌트 컬럼
        // (company_ref)은 별도 컬럼으로 emit되며 둘 다 같은 값을 담는다.
        @ManyToOne
        @MapsId("companyRef")
        @JoinColumn(name = "company_id")
        private Company company;

        Branch() {
        }

        Branch(Long branchNo, String city) {
            this.id = new BranchId(null, branchNo);
            this.city = city;
        }

        Long getCompanyRef() {
            return id == null ? null : id.companyRef;
        }

        Long getBranchNo() {
            return id == null ? null : id.branchNo;
        }

        String getCity() {
            return city;
        }

        Company getCompany() {
            return company;
        }

        void setCompany(Company company) {
            this.company = company;
        }
    }
}
