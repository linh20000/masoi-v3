CREATE TABLE IF NOT EXISTS schema_migrations (version VARCHAR(100) PRIMARY KEY, applied_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3));
CREATE TABLE IF NOT EXISTS rooms (
 id CHAR(36) PRIMARY KEY, code VARCHAR(6) NOT NULL UNIQUE, host_name VARCHAR(32) NOT NULL,
 host_token_hash CHAR(64) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'LOBBY', version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS players (
 id CHAR(36) PRIMARY KEY, room_id CHAR(36) NOT NULL, display_name VARCHAR(32) NOT NULL,
 token_hash CHAR(64) NOT NULL UNIQUE, connected BOOLEAN NOT NULL DEFAULT TRUE,
 card_revealed BOOLEAN NOT NULL DEFAULT FALSE, host_knows_role BOOLEAN NOT NULL DEFAULT FALSE,
 joined_at DATETIME(3) NOT NULL, UNIQUE KEY uq_player_name (room_id, display_name), KEY idx_players_room (room_id),
 CONSTRAINT fk_players_room FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS card_definitions (
 id VARCHAR(40) PRIMARY KEY, name VARCHAR(80) NOT NULL, faction VARCHAR(32) NOT NULL,
 description VARCHAR(400) NOT NULL, image_url VARCHAR(300)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS room_cards (
 room_id CHAR(36) NOT NULL, card_id VARCHAR(40) NOT NULL, quantity INT NOT NULL CHECK(quantity >= 0),
 PRIMARY KEY(room_id,card_id), CONSTRAINT fk_room_cards_room FOREIGN KEY(room_id) REFERENCES rooms(id) ON DELETE CASCADE,
 CONSTRAINT fk_room_cards_definition FOREIGN KEY(card_id) REFERENCES card_definitions(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS player_cards (
 id CHAR(36) PRIMARY KEY, room_id CHAR(36) NOT NULL, player_id CHAR(36) NOT NULL, card_id VARCHAR(40) NOT NULL,
 dealt_at DATETIME(3) NOT NULL, UNIQUE KEY uq_player_card (room_id,player_id), KEY idx_player_cards_room (room_id),
 CONSTRAINT fk_player_cards_room FOREIGN KEY(room_id) REFERENCES rooms(id) ON DELETE CASCADE,
 CONSTRAINT fk_player_cards_player FOREIGN KEY(player_id) REFERENCES players(id) ON DELETE CASCADE,
 CONSTRAINT fk_player_cards_definition FOREIGN KEY(card_id) REFERENCES card_definitions(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS special_role_states (
 id CHAR(36) PRIMARY KEY, room_id CHAR(36) NOT NULL, player_id CHAR(36) NOT NULL, role_id VARCHAR(40) NOT NULL,
 state JSON NOT NULL, updated_at DATETIME(3) NOT NULL, UNIQUE KEY uq_special_state(room_id,player_id,role_id),
 CONSTRAINT fk_special_room FOREIGN KEY(room_id) REFERENCES rooms(id) ON DELETE CASCADE,
 CONSTRAINT fk_special_player FOREIGN KEY(player_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB;
