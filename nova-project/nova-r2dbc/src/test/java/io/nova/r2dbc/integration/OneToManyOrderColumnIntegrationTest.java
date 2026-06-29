package io.nova.r2dbc.integration;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code @OneToMany(mappedBy)} + {@code @OrderColumn}이 H2 in-memory R2DBC driver와 end-to-end로 동작하는지
 * 검증한다 — child 테이블에 추가된 순서 컬럼 DDL, cascade save 시 0..n-1 인덱스 기록, findById 시 순서 컬럼으로
 * child 순서 복원, 재정렬 후 재인덱싱.
 */
class OneToManyOrderColumnIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        // playlist + track 테이블 생성. track 테이블에는 @OrderColumn 순서 컬럼(tracks_order)이 ALTER로 더해진다.
        schema.create(Playlist.class, Track.class).block();
    }

    @Test
    void restoresChildOrderFromOrderColumn() {
        Playlist playlist = new Playlist();
        playlist.add(new Track("gamma"));
        playlist.add(new Track("alpha"));
        playlist.add(new Track("beta"));
        Long id = support.operations().save(playlist).map(Playlist::getId).block();

        StepVerifier.create(support.operations().findById(Playlist.class, id))
                .assertNext(loaded -> assertEquals(List.of("gamma", "alpha", "beta"), titles(loaded)))
                .verifyComplete();
    }

    @Test
    void reindexesChildOrderAfterReorder() {
        Playlist playlist = new Playlist();
        playlist.add(new Track("gamma"));
        playlist.add(new Track("alpha"));
        playlist.add(new Track("beta"));
        Long id = support.operations().save(playlist).map(Playlist::getId).block();

        Playlist loaded = support.operations().findById(Playlist.class, id).block();
        // 기존 child 인스턴스(id 보존)를 의도적으로 id 순서와 다른 순서로 재배열한다 — 순서 컬럼이 복원의 근거임을 증명.
        List<Track> reordered = new ArrayList<>(loaded.getTracks());
        reordered.sort((a, b) -> a.getTitle().compareTo(b.getTitle())); // alpha, beta, gamma
        loaded.getTracks().clear();
        loaded.getTracks().addAll(reordered);
        support.operations().save(loaded).block();

        StepVerifier.create(support.operations().findById(Playlist.class, id))
                .assertNext(reloaded -> assertEquals(List.of("alpha", "beta", "gamma"), titles(reloaded)))
                .verifyComplete();
    }

    @Test
    void reindexesAfterRemoveAndAdd() {
        Playlist playlist = new Playlist();
        playlist.add(new Track("a"));
        playlist.add(new Track("b"));
        playlist.add(new Track("c"));
        Long id = support.operations().save(playlist).map(Playlist::getId).block();

        Playlist loaded = support.operations().findById(Playlist.class, id).block();
        // c 제거, d 추가, 순서 b, d, a 로 재배열 → 인덱스가 0..n-1로 재계산되어야 한다.
        Track a = loaded.getTracks().stream().filter(t -> t.getTitle().equals("a")).findFirst().orElseThrow();
        Track b = loaded.getTracks().stream().filter(t -> t.getTitle().equals("b")).findFirst().orElseThrow();
        loaded.getTracks().clear();
        loaded.add(b);
        loaded.add(new Track("d"));
        loaded.add(a);
        support.operations().save(loaded).block();

        StepVerifier.create(support.operations().findById(Playlist.class, id))
                .assertNext(reloaded -> assertEquals(List.of("b", "d", "a"), titles(reloaded)))
                .verifyComplete();
    }

    private static List<String> titles(Playlist playlist) {
        return playlist.getTracks().stream().map(Track::getTitle).toList();
    }

    @Entity
    @Table(name = "playlist2")
    public static class Playlist {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private String name = "p";

        @OneToMany(mappedBy = "playlist", targetEntity = Track.class,
                cascade = CascadeType.ALL, orphanRemoval = true)
        @OrderColumn
        private List<Track> tracks = new ArrayList<>();

        public Playlist() {
        }

        public void add(Track track) {
            track.setPlaylist(this);
            tracks.add(track);
        }

        public Long getId() {
            return id;
        }

        public List<Track> getTracks() {
            return tracks;
        }
    }

    @Entity
    @Table(name = "track")
    public static class Track {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private String title;

        @ManyToOne
        @JoinColumn(name = "playlist_id")
        private Playlist playlist;

        public Track() {
        }

        public Track(String title) {
            this.title = title;
        }

        public Long getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public Playlist getPlaylist() {
            return playlist;
        }

        public void setPlaylist(Playlist playlist) {
            this.playlist = playlist;
        }
    }
}
