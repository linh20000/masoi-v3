import './env.js';
import Fastify, { FastifyReply, FastifyRequest } from 'fastify';
import cors from '@fastify/cors';
import websocket from '@fastify/websocket';
import mysql, { PoolConnection, RowDataPacket } from 'mysql2/promise';
import { randomBytes, randomInt, createHash, randomUUID, timingSafeEqual } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { WebSocket } from 'ws';
import './migrate.js';

type Obj = any;
type Room = any;
class ApiError extends Error { constructor(public status: number, public code: string, message: string) { super(message); } }
const pool = mysql.createPool({ host: process.env.MYSQL_HOST ?? 'localhost', port: Number(process.env.MYSQL_PORT ?? 3306), user: process.env.MYSQL_USER ?? 'theveil', password: process.env.MYSQL_PASSWORD ?? '', database: process.env.MYSQL_DATABASE ?? 'theveil', waitForConnections: true, connectionLimit: 10, timezone: 'Z', charset: 'utf8mb4' });
const app = Fastify({ logger: true, bodyLimit: 1024 * 1024 });
const sockets = new Map<string, Set<WebSocket>>();
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const now = () => new Date();
const hash = (s: string) => createHash('sha256').update(s).digest('hex');
const secret = () => randomBytes(32).toString('base64url');
const randomCode = () => Array.from({ length: 6 }, () => ALPHABET[randomInt(ALPHABET.length)]).join('');
const codeOf = (s: string) => String(s ?? '').trim().toUpperCase();
const id = () => randomUUID();
const err = (status: number, code: string, message: string): never => { throw new ApiError(status, code, message); };
const equal = (a: string, b: string) => { const aa = Buffer.from(a), bb = Buffer.from(b); return aa.length === bb.length && timingSafeEqual(aa, bb); };
const parseJson = (v: any): Obj => { if (!v) return {}; if (typeof v === 'object') return v; try { return JSON.parse(String(v)); } catch { return {}; } };
const list = (v: any): string[] => Array.isArray(v) ? v.filter((x) => typeof x === 'string') : [];
const bearer = (r: FastifyRequest) => { const h = r.headers.authorization; return h?.startsWith('Bearer ') ? h.slice(7) : null; };

async function query<T extends RowDataPacket[]>(sql: string, values: any[] = [], c?: PoolConnection): Promise<T> { const [rows] = await (c ?? pool).execute<T>(sql, values); return rows; }
async function exec(sql: string, values: any[] = [], c?: PoolConnection) { return (c ?? pool).execute(sql, values); }
async function tx<T>(fn: (c: PoolConnection) => Promise<T>): Promise<T> { const c = await pool.getConnection(); try { await c.beginTransaction(); const v = await fn(c); await c.commit(); return v; } catch (e) { await c.rollback(); throw e; } finally { c.release(); } }
async function getRoom(code: string, c?: PoolConnection, lock = false): Promise<Room> { const rows = await query<Room[]>(`SELECT * FROM rooms WHERE code=? ${lock ? 'FOR UPDATE' : ''}`, [codeOf(code)], c); if (!rows[0]) err(404, 'ROOM_NOT_FOUND', 'Room code was not found.'); return rows[0]; }
async function players(roomId: string, c?: PoolConnection): Promise<Obj[]> { return query<Obj[]>(`SELECT * FROM players WHERE room_id=? ORDER BY joined_at,id`, [roomId], c); }
async function card(roomId: string, playerId: string, c?: PoolConnection): Promise<Obj | undefined> { return (await query<Obj[]>(`SELECT * FROM player_cards WHERE room_id=? AND player_id=?`, [roomId, playerId], c))[0]; }
async function state(roomId: string, playerId: string, roleId: string, c?: PoolConnection): Promise<Obj> { const row = (await query<Obj[]>(`SELECT state FROM special_role_states WHERE room_id=? AND player_id=? AND role_id=?`, [roomId, playerId, roleId], c))[0]; return parseJson(row?.state); }
async function saveState(roomId: string, playerId: string, roleId: string, value: Obj, c: PoolConnection) { await exec(`INSERT INTO special_role_states(id,room_id,player_id,role_id,state,updated_at) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE state=VALUES(state),updated_at=VALUES(updated_at)`, [id(), roomId, playerId, roleId, JSON.stringify(value), now()], c); }
async function changed(room: Room, c: PoolConnection) { room.version = Number(room.version) + 1; room.updated_at = now(); await exec('UPDATE rooms SET version=?,updated_at=? WHERE id=?', [room.version, room.updated_at, room.id], c); }
function publish(code: string, status: string) { const type = status === 'CLOSED' ? 'ROOM_CLOSED' : 'ROOM_UPDATED'; for (const ws of sockets.get(code) ?? []) { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type, code })); if (status === 'CLOSED' && ws.readyState === WebSocket.OPEN) ws.close(4001, 'ROOM_CLOSED'); } }
async function finishChanged(room: Room) { publish(room.code, room.status); }
function name(value: any) { const n = String(value ?? '').trim(); if (n.length < 2 || n.length > 32) err(400, 'INVALID_NAME', 'Name must contain 2 to 32 characters.'); return n; }
async function authenticate(room: Room, token: string | null, c?: PoolConnection) { if (!token) err(401, 'SESSION_REQUIRED', 'A room session is required.'); if (equal(room.host_token_hash, hash(token!))) return; const p = await query<Obj[]>(`SELECT id FROM players WHERE room_id=? AND token_hash=?`, [room.id, hash(token!)], c); if (!p.length) err(403, 'SESSION_INVALID', 'This session cannot access the room.'); }
async function host(room: Room, token: string | null) { if (!token || !equal(room.host_token_hash, hash(token))) err(403, 'HOST_ONLY', 'Only the host can perform this action.'); }
async function playerSession(room: Room, token: string | null, c?: PoolConnection) { if (!token) err(401, 'SESSION_REQUIRED', 'A player session is required.'); const rows = await query<Obj[]>(`SELECT * FROM players WHERE room_id=? AND token_hash=?`, [room.id, hash(token!)], c); if (!rows[0]) err(403, 'PLAYER_ONLY', 'A player session is required.'); return rows[0]; }
async function definitions(ids?: string[], c?: PoolConnection): Promise<Obj[]> { if (ids) { if (!ids.length) return []; return query<Obj[]>(`SELECT * FROM card_definitions WHERE id IN (${ids.map(() => '?').join(',')})`, ids, c); } return query<Obj[]>('SELECT * FROM card_definitions ORDER BY name', [], c); }
async function publicRoom(room: Room, reveal: boolean, c?: PoolConnection) {
 const roster = await players(room.id, c); const out = [];
 for (const p of roster) { const pc = await card(room.id, p.id, c); let roleId = null, roleName = null; const knows = reveal && !!p.host_knows_role && !!pc; if (knows) { const s = await state(room.id, p.id, pc.card_id, c); roleId = s.activeRoleId || pc.card_id; roleName = (await query<Obj[]>('SELECT name FROM card_definitions WHERE id=?', [roleId], c))[0]?.name ?? null; } out.push({ id:p.id, name:p.display_name, connected:!!p.connected, hasCard:!!pc, revealed:reveal && !!p.card_revealed, roleKnown:knows, revealedRoleId:roleId, revealedRoleName:roleName }); }
 const deck = await query<Obj[]>(`SELECT cd.id roleId,cd.name,cd.faction,rc.quantity count FROM room_cards rc JOIN card_definitions cd ON cd.id=rc.card_id WHERE rc.room_id=? ORDER BY cd.name`, [room.id], c);
 return { code:room.code, hostName:room.host_name, status:room.status, version:Number(room.version), createdAt:new Date(room.created_at).toISOString(), players:out, deck };
}
function validChoiceIndex(raw: any, options: string[]) { const i = Number.parseInt(String(raw ?? ''), 10); if (!Number.isInteger(i) || i < 0 || i >= options.length) err(400, 'INVALID_CHOICE', 'Choose one of the available facedown cards.'); return options[i]; }
async function validTarget(room: Room, owner: Obj, raw: any, c: PoolConnection) { const target = String(raw ?? ''); const rows = await query<Obj[]>('SELECT id FROM players WHERE id=? AND room_id=?', [target, room.id], c); if (!rows.length || target === owner.id) err(400, 'INVALID_TARGET', 'Choose another player in this room.'); return target; }
function authHeader(req: FastifyRequest) { return bearer(req); }
function reqCode(req: FastifyRequest) { return (req.params as any).code as string; }
async function route(method: string, url: string, handler: (req: FastifyRequest, reply: FastifyReply) => Promise<any>) { app.route({ method: method as any, url, handler: async (req, reply) => handler(req, reply) }); }

await app.register(cors, { origin: [process.env.HOST_ORIGIN ?? 'http://localhost:5173', 'http://localhost:5173'], methods: ['GET','POST','PUT','OPTIONS'], allowedHeaders: ['Authorization','Content-Type'] });
await app.register(websocket);
app.setErrorHandler((e, _req, reply) => { if (e instanceof ApiError) return reply.code(e.status).send({ code:e.code, message:e.message }); app.log.error(e); return reply.code(500).send({ code:'INTERNAL_ERROR', message:'The request could not be completed.' }); });

await route('POST','/api/rooms', async (req, reply) => {
 const body=req.body as Obj; const hostName=name(body?.hostName); const token=secret(); const roomId=id(); const t=now();
 const result=await tx(async c=>{ let code=''; for(let i=0;i<10;i++){code=randomCode();if(!(await query<Obj[]>('SELECT id FROM rooms WHERE code=?',[code],c)).length)break;} await exec(`INSERT INTO rooms(id,code,host_name,host_token_hash,status,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)`,[roomId,code,hostName,hash(token),'LOBBY',1,t,t],c); const r=await getRoom(code,c); return {code,room:await publicRoom(r,true,c)}; });
 return reply.code(201).send({code:result.code,sessionToken:token,playerId:null,room:result.room});
});
await route('POST','/api/rooms/:code/join', async(req,reply)=>{
 const code=reqCode(req);const body=req.body as Obj;const displayName=name(body?.displayName);const token=secret();
 const result=await tx(async c=>{const room=await getRoom(code,c,true);if(room.status!=='LOBBY')err(409,'ROOM_CLOSED','This room is no longer accepting players.');const roster=await players(room.id,c);let p=roster.find(x=>String(x.display_name).toLowerCase()===displayName.toLowerCase());let playerId:string|null=null;
 if(p){if(p.connected)err(409,'NAME_TAKEN','That name is already in this room.');playerId=p.id;await exec('UPDATE players SET token_hash=?,connected=TRUE WHERE id=?',[hash(token),p.id],c);}
 else{if(roster.length>=40)err(409,'ROOM_FULL','This room has reached its player limit.');playerId=id();await exec('INSERT INTO players(id,room_id,display_name,token_hash,connected,card_revealed,host_knows_role,joined_at) VALUES(?,?,?,?,TRUE,FALSE,FALSE,?)',[playerId,room.id,displayName,hash(token),now()],c);}
 await changed(room,c);return {room:await publicRoom(room,false,c),playerId,code:room.code};});await finishChanged({code:result.code,status:'LOBBY'} as Room);return reply.code(201).send({code:result.code,sessionToken:token,playerId:result.playerId,room:result.room});
});
await route('GET','/api/rooms/:code',async(req)=>{const r=await getRoom(reqCode(req));const token=authHeader(req);await authenticate(r,token);return publicRoom(r,equal(r.host_token_hash,hash(token!)));});
await route('POST','/api/rooms/:code/leave',async(req,reply)=>{const r=await tx(async c=>{const room=await getRoom(reqCode(req),c,true);const p=await playerSession(room,authHeader(req),c);if(p.connected){await exec('UPDATE players SET connected=FALSE WHERE id=?',[p.id],c);await changed(room,c);}return room;});await finishChanged(r);return reply.code(204).send();});
await route('PUT','/api/rooms/:code/deck',async(req)=>{const body=req.body as Obj;const cards=body?.cards;if(!Array.isArray(cards))err(400,'INVALID_DECK','Card list is required.');const r=await tx(async c=>{const room=await getRoom(reqCode(req),c,true);await host(room,authHeader(req));if(room.status!=='LOBBY')err(409,'SESSION_ACTIVE','Deck cannot change after dealing. Recall all cards first.');const seen=new Set<string>();let total=0;for(const x of cards){if(!x||typeof x.roleId!=='string'||!Number.isInteger(x.count)||x.count<0||x.count>40||seen.has(x.roleId))err(400,'INVALID_DECK','Card entries must have unique roles and counts from 0 to 40.');seen.add(x.roleId);if(!(await query<Obj[]>('SELECT id FROM card_definitions WHERE id=?',[x.roleId],c)).length)err(400,'UNKNOWN_ROLE','A selected role does not exist.');total+=x.count;}if(total>40)err(400,'DECK_TOO_LARGE','The deck cannot contain more than 40 cards.');await exec('DELETE FROM room_cards WHERE room_id=?',[room.id],c);for(const x of cards)if(x.count>0)await exec('INSERT INTO room_cards(room_id,card_id,quantity) VALUES(?,?,?)',[room.id,x.roleId,x.count],c);await changed(room,c);return {room,body:await publicRoom(room,true,c)};});await finishChanged(r.room);return r.body;});
await route('POST','/api/rooms/:code/deal',async(req)=>{const r=await tx(async c=>{const room=await getRoom(reqCode(req),c,true);await host(room,authHeader(req));if(room.status==='CLOSED')err(409,'ROOM_CLOSED','This session has ended.');if(room.status!=='LOBBY')err(409,'ALREADY_DEALT','Cards have already been dealt. Recall them before dealing again.');const roster=(await players(room.id,c)).filter(p=>p.connected);if(!roster.length)err(409,'NO_PLAYERS','At least one player must join before dealing.');const cards=await query<Obj[]>('SELECT card_id,quantity FROM room_cards WHERE room_id=?',[room.id],c);const deck=cards.flatMap(x=>Array(Number(x.quantity)).fill(x.card_id));if(deck.length!==roster.length)err(409,'DECK_COUNT_MISMATCH',`The selected deck contains ${deck.length} cards for ${roster.length} connected players. Set role quantities so the total matches the connected player count.`);if(!deck.length)err(409,'EMPTY_DECK','Select cards before dealing.');for(let i=deck.length-1;i>0;i--){const j=randomInt(i+1);[deck[i],deck[j]]=[deck[j],deck[i]];}for(let i=0;i<roster.length;i++){await exec('INSERT INTO player_cards(id,room_id,player_id,card_id,dealt_at) VALUES(?,?,?,?,?)',[id(),room.id,roster[i].id,deck[i],now()],c);await exec('UPDATE players SET card_revealed=FALSE,host_knows_role=FALSE WHERE id=?',[roster[i].id],c);}room.status='DEALT';await exec('UPDATE rooms SET status=? WHERE id=?',['DEALT',room.id],c);await changed(room,c);return {room,body:await publicRoom(room,true,c)};});await finishChanged(r.room);return r.body;});
await route('POST','/api/rooms/:code/recall',async(req)=>{const body=req.body as Obj|null;const r=await tx(async c=>{const room=await getRoom(reqCode(req),c,true);await host(room,authHeader(req));if(room.status==='CLOSED')err(409,'ROOM_CLOSED','This session has ended.');const ids=body?.playerIds as string[]|undefined;if(!ids?.length){await exec('DELETE FROM player_cards WHERE room_id=?',[room.id],c);await exec('DELETE FROM special_role_states WHERE room_id=?',[room.id],c);await exec('UPDATE players SET card_revealed=FALSE,host_knows_role=FALSE WHERE room_id=?',[room.id],c);}else{const unique=[...new Set(ids)];const roster=await players(room.id,c);if(unique.some(x=>!roster.some(p=>p.id===x)))err(400,'PLAYER_NOT_IN_ROOM','One or more selected players are not in this room.');for(const pid of unique){await exec('DELETE FROM player_cards WHERE room_id=? AND player_id=?',[room.id,pid],c);await exec('DELETE FROM special_role_states WHERE room_id=? AND player_id=?',[room.id,pid],c);await exec('UPDATE players SET card_revealed=FALSE,host_knows_role=FALSE WHERE id=?',[pid],c);}}
 const n=await query<Obj[]>('SELECT COUNT(*) n FROM player_cards WHERE room_id=?',[room.id],c);room.status=Number(n[0].n)===0?'LOBBY':'DEALT';await exec('UPDATE rooms SET status=? WHERE id=?',[room.status,room.id],c);await changed(room,c);return {room,body:await publicRoom(room,true,c)};});await finishChanged(r.room);return r.body;});
await route('POST','/api/rooms/:code/end',async(req)=>{const r=await tx(async c=>{const room=await getRoom(reqCode(req),c,true);await host(room,authHeader(req));if(room.status==='CLOSED')err(409,'ROOM_CLOSED','This session has already ended.');room.status='CLOSED';await exec('UPDATE rooms SET status=? WHERE id=?',['CLOSED',room.id],c);await changed(room,c);return {room,body:await publicRoom(room,true,c)};});await finishChanged(r.room);return r.body;});

await route('GET','/api/rooms/:code/me',async(req)=>{const room=await getRoom(reqCode(req));const p=await playerSession(room,authHeader(req));if(room.status==='CLOSED')err(410,'ROOM_CLOSED','This session has ended.');const pc=await card(room.id,p.id);if(!pc)err(404,'CARD_NOT_DEALT','Your card has not been dealt yet.');const s={...await state(room.id,p.id,pc.card_id)};let shown=s.activeRoleId;if(['actor','thief'].includes(pc.card_id)){const options=list(s.options);const selected=s.selectedRoleId;const active=s.activeRoleId;const status=s.status;delete s.selectedRoleId;delete s.activeRoleId;shown=selected&&active&&['ACTIVE','COMPLETED'].includes(status)?active:pc.card_id;if(selected)s.selectedOption=String(options.indexOf(selected));s.options=options.map((_,i)=>String(i));}else if(!shown)shown=pc.card_id;const d=(await query<Obj[]>('SELECT * FROM card_definitions WHERE id=?',[shown]))[0];return {roleId:d.id,name:d.name,faction:d.faction,description:d.description,imageUrl:d.image_url,sourceRoleId:pc.card_id,specialState:s,revealed:!!p.card_revealed};});

await route('POST','/api/rooms/:code/card-reveal',async(req,reply)=>{const body=req.body as Obj;let didChange=false;const result=await tx(async c=>{const room=await getRoom(reqCode(req),c,true);const p=await playerSession(room,authHeader(req),c);if(room.status!=='DEALT'||!(await card(room.id,p.id,c)))err(409,'CARD_NOT_DEALT','Your card is not currently dealt.');const value=!!body?.revealed;didChange=!!p.card_revealed!==value||(value&&!p.host_knows_role);if(didChange){await exec('UPDATE players SET card_revealed=?,host_knows_role=IF(?,TRUE,host_knows_role) WHERE id=?',[value,value,p.id],c);await changed(room,c);}return room;});if(didChange)await finishChanged(result);return reply.code(204).send();});

await route('POST','/api/rooms/:code/special-choice',async(req,reply)=>{
 const body=req.body as Obj;
 const result=await tx(async c=>{
  const room=await getRoom(reqCode(req),c,true);const p=await playerSession(room,authHeader(req),c);
  if(room.status!=='DEALT')err(409,'SESSION_NOT_DEALT','Special choices are available during a dealt session.');
  const role=body?.roleId;if(!['wild_child','half_wolf','actor','thief','shadow'].includes(role))err(400,'UNKNOWN_SPECIAL_ROLE','This special role is not supported.');
  const pc=await card(room.id,p.id,c);if(!pc)err(409,'CARD_NOT_DEALT','Special choices are available after dealing.');
  if(pc.card_id!==role)err(403,'ROLE_MISMATCH','Only the holder of this role may submit its private choice.');
  const choice=body.choice??{};if(Object.keys(choice).length>4||Object.values(choice).some((x:any)=>typeof x!=='string'||x.length>100))err(400,'INVALID_CHOICE','Choice payload is invalid.');
  const saved=await state(room.id,p.id,role,c);
  if(['wild_child','shadow'].includes(role)){saved.targetPlayerId=await validTarget(room,p,choice.target,c);saved.status='CHOICE_SUBMITTED';}
  else if(role==='half_wolf'){if(!['DÂN LÀNG','MA SÓI'].includes(choice.faction))err(400,'INVALID_FACTION','Choose Dân Làng or Ma Sói.');saved.faction=choice.faction;saved.activeRoleId=choice.faction==='MA SÓI'?'werewolf':'villager';saved.status='COMPLETED';}
  else {
   const options=list(saved.options);
   if(role==='thief'&&!options.length)err(409,'THIEF_OPTIONS_NOT_READY','The host has not prepared your two reserve cards yet.');
   const selected=validChoiceIndex(choice.selectedOption,options);
   if(role==='actor'&&saved.status==='ACTIVE'&&saved.selectedRoleId===selected)return room;
   if(role==='actor'&&(['ACTIVE','DEPLETED'].includes(saved.status)||!options.length||!options.includes(selected)))err(409,'ACTOR_NOT_ACTIVE','The host has not prepared an available Actor role card.');
   if(role==='thief'&&!options.includes(selected))err(409,'THIEF_OPTIONS_NOT_READY','The host has not prepared your two reserve cards yet.');
   saved.selectedRoleId=selected;saved.activeRoleId=selected;saved.status=role==='actor'?'ACTIVE':'COMPLETED';
  }
  await saveState(room.id,p.id,role,saved,c);if(saved.activeRoleId&&saved.activeRoleId!==pc.card_id)await exec('UPDATE players SET host_knows_role=TRUE WHERE id=?',[p.id],c);await changed(room,c);return room;
 });
 await finishChanged(result);return reply.code(204).send();
});

await route('GET','/api/rooms/:code/special-roles',async(req)=>{const room=await getRoom(reqCode(req));await host(room,authHeader(req));const cards=await query<Obj[]>(`SELECT pc.*,p.display_name FROM player_cards pc JOIN players p ON p.id=pc.player_id WHERE pc.room_id=?`,[room.id]);const out=[];for(const pc of cards){if(!['thief','wild_child','actor','half_wolf','shadow'].includes(pc.card_id))continue;const s=await state(room.id,pc.player_id,pc.card_id);const target=s.targetPlayerId?(await query<Obj[]>('SELECT display_name FROM players WHERE id=?',[s.targetPlayerId]))[0]:null;const selected=s.selectedRoleId?(await query<Obj[]>('SELECT name FROM card_definitions WHERE id=?',[s.selectedRoleId]))[0]:null;out.push({playerId:pc.player_id,playerName:pc.display_name,roleId:pc.card_id,status:s.status??null,targetPlayerId:s.targetPlayerId??null,targetName:target?.display_name??null,faction:s.faction??null,selectedRoleId:s.selectedRoleId??null,selectedRoleName:selected?.name??null,options:list(s.options)});}return out;});

await route('POST','/api/rooms/:code/special-roles/:playerId',async(req,reply)=>{const body=req.body as Obj;const result=await tx(async c=>{const room=await getRoom(reqCode(req),c,true);await host(room,authHeader(req));if(room.status!=='DEALT')err(409,'SESSION_NOT_DEALT','Special role actions are available after dealing.');if(!body?.roleId||!body?.action)err(400,'INVALID_SPECIAL_ACTION','A role and action are required.');const playerId=(req.params as any).playerId;const pc=await card(room.id,playerId,c);if(!pc)err(404,'PLAYER_CARD_NOT_FOUND','That player has no dealt card.');if(pc.card_id!==body.roleId)err(409,'ROLE_MISMATCH','The player no longer holds this special role.');const s=await state(room.id,playerId,pc.card_id,c);const options=Array.isArray(body.options)?body.options:[];const requireRole=(role:string)=>{if(pc.card_id!==role)err(409,'ROLE_MISMATCH','The player does not hold this special role.');};
 switch(body.action){case 'SET_THIEF_OPTIONS': requireRole('thief'); if(options.length!==2||new Set(options).size!==2||options.includes('thief')||(await definitions(options,c)).length!==2)err(400,'INVALID_ROLE_OPTIONS','Choose the required number of distinct, valid role cards.');s.options=options;s.status='AWAITING_CHOICE';break;
 case 'DRAW_THIEF_OPTIONS':{requireRole('thief');const pool=(await definitions(undefined,c)).map(x=>x.id).filter(x=>x!=='thief');for(let i=pool.length-1;i>0;i--){const j=randomInt(i+1);[pool[i],pool[j]]=[pool[j],pool[i]];}s.options=pool.slice(0,2);s.status='AWAITING_CHOICE';break;}
 case 'DRAW_ACTOR_OPTIONS':{requireRole('actor');if(list(s.options).length||['ACTIVE','DEPLETED'].includes(s.status))err(409,'ACTOR_INVENTORY_ALREADY_PREPARED','The Actor inventory has already been drawn or depleted.');const active=(await query<Obj[]>('SELECT card_id FROM room_cards WHERE room_id=? AND quantity>0',[room.id],c)).map(x=>x.card_id);let pool=(await definitions(undefined,c)).map(x=>x.id).filter(x=>x!=='actor'&&!active.includes(x));if(pool.length<3)err(409,'ACTOR_INVENTORY_TOO_SMALL','At least three unused roles are required to draw the Actor inventory.');for(let i=pool.length-1;i>0;i--){const j=randomInt(i+1);[pool[i],pool[j]]=[pool[j],pool[i]];}s.options=pool.slice(0,3);delete s.selectedRoleId;delete s.activeRoleId;s.status='AWAITING_CHOICE';break;}
 case 'RECALL_ACTOR_CARD': case 'REVOKE_ACTOR':{requireRole('actor');const used=s.selectedRoleId;const inventory=list(s.options).filter(x=>x!==used);if(s.status!=='ACTIVE'||!used||inventory.length===list(s.options).length)err(409,'ACTOR_CARD_NOT_ACTIVE','There is no active Actor card to recall.');s.options=inventory;delete s.selectedRoleId;delete s.activeRoleId;s.status=inventory.length?'AWAITING_CHOICE':'DEPLETED';break;}
 case 'TRANSFORM_WILD_CHILD':requireRole('wild_child');if(!s.targetPlayerId)err(409,'WILD_CHILD_IDOL_NOT_SET','The Wild Child has not selected an idol.');s.activeRoleId='werewolf';s.status='TRANSFORMED';await exec('UPDATE players SET host_knows_role=TRUE WHERE id=?',[playerId],c);break;
 case 'TRANSFER_SHADOW':{requireRole('shadow');if(!s.targetPlayerId)err(409,'SHADOW_TARGET_NOT_SET','The Shadow has not selected a target.');const target=await card(room.id,s.targetPlayerId,c);if(!target)err(409,'TARGET_CARD_MISSING','The target has no role card to transfer.');s.activeRoleId=target.card_id;s.selectedRoleId=target.card_id;s.status='TRANSFERRED';await exec('UPDATE players SET host_knows_role=TRUE WHERE id=?',[playerId],c);break;}
 default:err(400,'UNKNOWN_SPECIAL_ACTION','This special action is not supported.');}
 await saveState(room.id,playerId,pc.card_id,s,c);await changed(room,c);return room;});await finishChanged(result);return reply.code(204).send();});

await app.register(async (fastify) => {
 fastify.get('/ws/rooms/:code',{ websocket:true },(socket,req)=>{
  const code=codeOf((req.params as any).code);let authenticated=false;let token='';let isHost=false;
  const origin=req.headers.origin;const allowed=new Set([process.env.HOST_ORIGIN??'http://localhost:5173','http://localhost:5173']);
  if(origin&&!allowed.has(origin)){socket.close(1008,'ORIGIN_NOT_ALLOWED');return;}
  socket.on('message',async raw=>{
   if(authenticated)return;
   try {
    const message=JSON.parse(raw.toString());const room=await getRoom(code);
    if(room.status==='CLOSED'){socket.close(4001,'ROOM_CLOSED');return;}
    if(message.type!=='AUTH'||typeof message.token!=='string'){socket.close(1008);return;}
    await authenticate(room,message.token);token=message.token;isHost=equal(room.host_token_hash,hash(token));(socket as any).__token=token;authenticated=true;
    if(!sockets.has(code))sockets.set(code,new Set());sockets.get(code)!.add(socket);
    if(!isHost){let didChange=false;const locked=await tx(async c=>{const r=await getRoom(code,c,true);if(r.status==='CLOSED')return r;const p=await playerSession(r,token,c);if(!p.connected){await exec('UPDATE players SET connected=TRUE WHERE id=?',[p.id],c);await changed(r,c);didChange=true;}return r;});if(didChange)publish(locked.code,locked.status);}
    socket.send(JSON.stringify({type:'AUTHENTICATED'}));
   }catch{socket.close(1008);}
  });
  socket.on('close',async()=>{
   sockets.get(code)?.delete(socket);if(!sockets.get(code)?.size)sockets.delete(code);if(!authenticated||isHost)return;
   const active=[...(sockets.get(code)??[])].some((s:any)=>s.__token===token);if(active)return;
   try{let didChange=false;const locked=await tx(async c=>{const r=await getRoom(code,c,true);const p=await playerSession(r,token,c);if(p.connected){await exec('UPDATE players SET connected=FALSE WHERE id=?',[p.id],c);await changed(r,c);didChange=true;}return r;});if(didChange)publish(locked.code,locked.status);}catch{}
  });
 });
});

app.get('/actuator/health',async()=>{await pool.query('SELECT 1');return {status:'UP'};});
const port=Number(process.env.PORT??8080);await app.listen({host:'0.0.0.0',port});
