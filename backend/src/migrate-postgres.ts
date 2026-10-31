import './env.js';
import mysql from 'mysql2/promise';
import pg from 'pg';

const sourceUrl=process.env.LEGACY_PG_URL;
if(!sourceUrl)throw new Error('Set LEGACY_PG_URL to the existing PostgreSQL connection string.');
const source=new pg.Client({connectionString:sourceUrl});
const target=await mysql.createConnection({host:process.env.MYSQL_HOST??'localhost',port:Number(process.env.MYSQL_PORT??3306),user:process.env.MYSQL_USER??'theveil',password:process.env.MYSQL_PASSWORD??'',database:process.env.MYSQL_DATABASE??'theveil',timezone:'Z'});
const copyTables=[
 ['rooms','id,code,host_name,host_token_hash,status,version,created_at,updated_at'],
 ['players','id,room_id,display_name,token_hash,connected,card_revealed,host_knows_role,joined_at'],
 ['room_cards','room_id,card_id,quantity'],
 ['player_cards','id,room_id,player_id,card_id,dealt_at'],
 ['special_role_states','id,room_id,player_id,role_id,state,updated_at'],
] as const;
await source.connect();
try {
 const defs=(await source.query('SELECT id,name,faction,description,image_url FROM card_definitions ORDER BY id')).rows;
 const rooms=(await source.query('SELECT COUNT(*)::int n FROM rooms')).rows;
 console.log(`Copying ${rooms[0].n} rooms and their related records.`);
 for(const [table,columns] of copyTables){
  const {rows}=await source.query(`SELECT ${columns} FROM ${table}`);
  if(!rows.length)continue;
  const cols=columns.split(',');const marks=cols.map(()=>'?').join(',');
  await target.beginTransaction();
  try{for(const row of rows){const values=cols.map((col)=>table==='special_role_states'&&col==='state'?JSON.stringify(row[col]):row[col]);await target.execute(`INSERT IGNORE INTO ${table}(${columns}) VALUES(${marks})`,values);}await target.commit();}
  catch(error){await target.rollback();throw error;}
  console.log(`${table}: ${rows.length} rows copied or already present.`);
 }
 // Preserve customized card catalog values from the source database.
 for(const d of defs)await target.execute('INSERT INTO card_definitions(id,name,faction,description,image_url) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE name=VALUES(name),faction=VALUES(faction),description=VALUES(description),image_url=VALUES(image_url)',[d.id,d.name,d.faction,d.description,d.image_url]);
}finally{await source.end();await target.end();}
