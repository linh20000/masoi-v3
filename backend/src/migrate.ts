import './env.js';
import mysql from 'mysql2/promise';
import { readdir, readFile } from 'node:fs/promises';
import { join } from 'node:path';

const db=mysql.createConnection({host:process.env.MYSQL_HOST??'localhost',port:Number(process.env.MYSQL_PORT??3306),user:process.env.MYSQL_USER??'theveil',password:process.env.MYSQL_PASSWORD??'',database:process.env.MYSQL_DATABASE??'theveil',multipleStatements:true,timezone:'Z'});
const conn=await db;
try {
 await conn.query('CREATE TABLE IF NOT EXISTS schema_migrations(version VARCHAR(100) PRIMARY KEY,applied_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)) ENGINE=InnoDB');
 const files=(await readdir(join(process.cwd(),'migrations'))).filter(x=>x.endsWith('.sql')).sort();
 for(const file of files){const [rows]=await conn.execute<any[]>('SELECT version FROM schema_migrations WHERE version=?',[file]);if(rows.length)continue;console.log(`Applying ${file}`);await conn.beginTransaction();try{await conn.query(await readFile(join(process.cwd(),'migrations',file),'utf8'));await conn.execute('INSERT INTO schema_migrations(version) VALUES(?)',[file]);await conn.commit();}catch(e){await conn.rollback();throw e;}}
} finally { await conn.end(); }
