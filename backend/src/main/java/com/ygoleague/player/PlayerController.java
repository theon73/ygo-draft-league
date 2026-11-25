package com.ygoleague.player;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/players")
public class PlayerController {

    public record PlayerDto(Long id, String displayName) {}

    private final PlayerRepository players;

    public PlayerController(PlayerRepository players) {
        this.players = players;
    }

    @GetMapping
    public List<PlayerDto> list() {
        return players.findAll(Sort.by("displayName")).stream()
                .map(p -> new PlayerDto(p.getId(), p.getDisplayName()))
                .toList();
    }
}
