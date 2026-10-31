CREATE TABLE rooms (
  id UUID PRIMARY KEY,
  code VARCHAR(6) NOT NULL UNIQUE,
  host_name VARCHAR(32) NOT NULL,
  host_token_hash CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'LOBBY',
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE players (
  id UUID PRIMARY KEY,
  room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
  display_name VARCHAR(32) NOT NULL,
  token_hash CHAR(64) NOT NULL UNIQUE,
  connected BOOLEAN NOT NULL DEFAULT true,
  joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(room_id, display_name)
);
CREATE INDEX idx_players_room ON players(room_id);
CREATE TABLE card_definitions (
  id VARCHAR(40) PRIMARY KEY,
  name VARCHAR(80) NOT NULL,
  faction VARCHAR(32) NOT NULL,
  description VARCHAR(400) NOT NULL,
  image_url VARCHAR(300)
);
CREATE TABLE room_cards (
  room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
  card_id VARCHAR(40) NOT NULL REFERENCES card_definitions(id),
  quantity INTEGER NOT NULL CHECK(quantity >= 0),
  PRIMARY KEY(room_id, card_id)
);
CREATE TABLE player_cards (
  id UUID PRIMARY KEY,
  room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
  player_id UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
  card_id VARCHAR(40) NOT NULL REFERENCES card_definitions(id),
  dealt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(room_id, player_id)
);
CREATE INDEX idx_player_cards_room ON player_cards(room_id);
CREATE TABLE special_role_states (
  id UUID PRIMARY KEY,
  room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
  player_id UUID NOT NULL REFERENCES players(id) ON DELETE CASCADE,
  role_id VARCHAR(40) NOT NULL,
  state JSONB NOT NULL DEFAULT '{}'::jsonb,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(room_id, player_id, role_id)
);
INSERT INTO card_definitions(id,name,faction,description,image_url) VALUES
 ('villager','Dân làng','DÂN LÀNG','Không có năng lực đặc biệt.',''),
 ('werewolf','Ma Sói','MA SÓI','Thức dậy cùng bầy sói trong đêm.',''),
 ('seer','Tiên tri','DÂN LÀNG','Mỗi đêm soi một người chơi.',''),
 ('witch','Phù thủy','DÂN LÀNG','Sở hữu bình cứu và bình độc.',''),
 ('guard','Bảo vệ','DÂN LÀNG','Mỗi đêm chọn một người để bảo vệ.',''),
 ('hunter','Thợ săn','DÂN LÀNG','Có thể kéo theo một người khi bị loại.',''),
 ('thief','Kẻ trộm','TRUNG LẬP','Lựa chọn vai theo quy tắc phiên chơi.',''),
 ('wild_child','Đứa trẻ hoang dã','TRUNG LẬP','Đêm đầu chọn một người làm thần tượng.',''),
 ('actor','Diễn viên','TRUNG LẬP','Chọn vai tạm thời theo quy tắc phiên chơi.',''),
 ('half_wolf','Nửa người nửa sói','TRUNG LẬP','Chọn phe Dân Làng hoặc Ma Sói.',''),
 ('shadow','Bóng Ma','TRUNG LẬP','Chọn mục tiêu theo quy tắc phiên chơi.','');
