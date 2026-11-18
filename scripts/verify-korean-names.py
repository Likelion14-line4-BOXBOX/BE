"""#61: isolated MySQL migration/seed checks and real-token HTTP verification. No DB is deleted."""
import argparse
import base64
import datetime
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time

BE = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--reference-seed', required=True, type=Path)
parser.add_argument('--mysql-bin', default='C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe')
parser.add_argument('--evidence', required=True, type=Path)
args = parser.parse_args()
checks, responses = [], []
mysql_args = [args.mysql_bin, '--default-character-set=utf8mb4', '--protocol=TCP', '-h', '127.0.0.1', '-P', '13306', '-u', 'root', '--batch', '--raw', '--skip-column-names']


def sql(query, database=None):
    return subprocess.run(mysql_args + ([database] if database else []), input=query,
                          capture_output=True, text=True, encoding='utf-8', check=True).stdout.strip()


def check(label, condition):
    assert condition, label
    checks.append(label)


def apply(path, database):
    return sql(path.read_text(encoding='utf-8-sig'), database)


identity = sql('SELECT @@datadir,@@port,@@version').split('\t')
check('isolated datadir and port', Path(identity[0]).resolve() == (BE/'build/verification/mysql-20261113').resolve() and identity[1] == '13306')
tag = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d_%H%M%S')
legacy, fresh = 'boxbox_korean_legacy_' + tag, 'boxbox_korean_http_' + tag
for database in [legacy, fresh]:
    check(database + ' is new', sql(f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{database}'") == '0')
    sql(f'CREATE DATABASE {database} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci')

old_schema = subprocess.run(['git', 'show', 'HEAD:deploy/db/schema.sql'], cwd=BE,
                            capture_output=True, text=True, encoding='utf-8', check=True).stdout
# The selected reference revision is required to precede #61's schema migration.
if '`name_ko`' in old_schema:
    old_schema = re.sub(r'^.*`name_ko`.*\n', '', old_schema, flags=re.M)
sql(old_schema, legacy)
apply(BE/'deploy/db/schema.sql', fresh)
for database in [legacy, fresh]:
    apply(args.reference_seed, database)
    apply(BE/'deploy/db/board-seed.sql', database)

columns = {}
for line in sql('SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name,ordinal_position', legacy).splitlines():
    table, column = line.split('\t')
    columns.setdefault(table, []).append(column)


def original_state(database):
    return {table: sql('SELECT ' + ','.join('`'+c+'`' for c in cols) + ' FROM `' + table + '` ORDER BY ' + ','.join('`'+c+'`' for c in cols), database)
            for table, cols in columns.items()}


before = original_state(legacy)
for _ in range(2):
    apply(BE/'deploy/db/migrations/061-korean-display-names.sql', legacy)
check('migration repeated without changing original columns or rows', before == original_state(legacy))
check('legacy schema has three nullable Korean columns', sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name='name_ko' AND is_nullable='YES'", legacy) == '3')
metadata_query = "SELECT table_name,column_name,column_type,is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name='name_ko' ORDER BY table_name"
check('fresh and migrated Korean columns agree', sql(metadata_query, legacy) == sql(metadata_query, fresh))
sql("UPDATE teams SET name_ko='직접 지정한 한국어' WHERE code='ferrari'", legacy)
original_key = sql('SELECT external_key FROM races WHERE season_year=2026 AND round_number=1', legacy)
sql("UPDATE races SET external_key='unmatched-61' WHERE season_year=2026 AND round_number=1", legacy)
sql("INSERT INTO races(season_year,round_number,name,country_name,circuit_name,circuit_key,weekend_starts_at,weekend_ends_at) VALUES(2025,21,'Las Vegas Grand Prix','United States','Las Vegas','152','2025-11-20','2025-11-23')", legacy)
apply(BE/'deploy/db/korean-name-seed.sql', legacy)
check('preexisting Korean name is preserved', sql("SELECT name_ko FROM teams WHERE code='ferrari'", legacy) == '직접 지정한 한국어')
check('same season/round with mismatched external key stays null', sql('SELECT name_ko IS NULL FROM races WHERE season_year=2026 AND round_number=1', legacy) == '1')
check('same English race name in another season stays null', sql('SELECT name_ko IS NULL FROM races WHERE season_year=2025 AND round_number=21', legacy) == '1')
sql(f"UPDATE races SET external_key='{original_key}' WHERE season_year=2026 AND round_number=1", legacy)
apply(BE/'deploy/db/korean-name-seed.sql', legacy)
check('all provided teams/drivers/2026 races localized', sql("SELECT (SELECT COUNT(*) FROM teams WHERE name_ko IS NOT NULL),(SELECT COUNT(*) FROM drivers WHERE name_ko IS NOT NULL),(SELECT COUNT(*) FROM races WHERE season_year=2026 AND name_ko IS NOT NULL)", legacy) == '11\t25\t23')
check('race translation uses source mapping', sql("SELECT name_ko FROM races WHERE season_year=2026 AND round_number=21 AND external_key='1300'", legacy) == '라스베이거스 그랑프리')
stable = sql('SELECT id,code,name,name_ko FROM teams ORDER BY id', legacy) + sql('SELECT id,code,name,name_ko FROM drivers ORDER BY id', legacy) + sql('SELECT id,season_year,round_number,name,name_ko FROM races ORDER BY id', legacy)
apply(BE/'deploy/db/korean-name-seed.sql', legacy)
check('seed is repeatable', stable == sql('SELECT id,code,name,name_ko FROM teams ORDER BY id', legacy) + sql('SELECT id,code,name,name_ko FROM drivers ORDER BY id', legacy) + sql('SELECT id,season_year,round_number,name,name_ko FROM races ORDER BY id', legacy))
after_names = original_state(legacy)
check('seed preserved original teams and drivers', all(before[t] == after_names[t] for t in columns if t != 'races'))
race_columns = ','.join('`'+c+'`' for c in columns['races'])
check('seed preserved original race fields', before['races'] == sql('SELECT '+race_columns+' FROM races WHERE season_year=2026 ORDER BY '+race_columns, legacy))
apply(BE/'deploy/db/korean-name-seed.sql', fresh)
team = sql("SELECT id FROM teams WHERE code='ferrari'", fresh)
driver = sql("SELECT id FROM drivers WHERE code='LEC'", fresh)
board = sql(f'SELECT id FROM boards WHERE team_id={team}', fresh)
driver_tag = sql(f"SELECT id FROM board_tags WHERE board_id={board} AND driver_id={driver}", fresh)
race = sql('SELECT id FROM races WHERE season_year=2026 AND round_number=21', fresh)
untranslated_race = sql('SELECT id FROM races WHERE season_year=2026 AND round_number=22', fresh)
sql(f'UPDATE races SET name_ko=NULL WHERE id={untranslated_race}', fresh)
sql(f"INSERT INTO chat_rooms(race_id,status,opens_at,closes_at) VALUES({race},'SCHEDULED',UTC_TIMESTAMP()-INTERVAL 1 HOUR,UTC_TIMESTAMP()+INTERVAL 3 HOUR),({untranslated_race},'SCHEDULED',UTC_TIMESTAMP()+INTERVAL 1 DAY,UTC_TIMESTAMP()+INTERVAL 2 DAY)", fresh)
room = sql(f'SELECT id FROM chat_rooms WHERE race_id={race}', fresh)
null_room = sql(f'SELECT id FROM chat_rooms WHERE race_id={untranslated_race}', fresh)
radio_state = {t: sql('SELECT * FROM '+t+' ORDER BY id', fresh) for t in ['boards','board_tags']}


def scrub(value):
    if isinstance(value, dict):
        return {k: '[REDACTED]' if k in {'password','accessToken','refreshToken'} else scrub(v) for k,v in value.items()}
    if isinstance(value, list): return [scrub(v) for v in value]
    return value


def request(path, expected=200, token=None, method='GET', body=None):
    headers = {'Authorization': 'Bearer '+token} if token else {}
    payload = None
    if body is not None:
        headers['Content-Type'] = 'application/json'
        payload = json.dumps(body, ensure_ascii=False).encode('utf-8')
    conn = http.client.HTTPConnection('127.0.0.1', port, timeout=10)
    conn.request(method, path, payload, headers)
    response = conn.getresponse()
    raw = response.read().decode('utf-8')
    value = json.loads(raw) if raw else None
    conn.close()
    responses.append(dict(method=method,path=path,status=response.status,response=scrub(value)))
    check(f'{method} {path} = {expected}', response.status == expected)
    return value


env = dict(os.environ, DB_URL=f'jdbc:mysql://127.0.0.1:13306/{fresh}?connectionTimeZone=UTC&characterEncoding=UTF-8', DB_USERNAME='root', DB_PASSWORD='',
           AUTH_JWT_SECRET=base64.b64encode(secrets.token_bytes(48)).decode(), AUTH_EXTENDED_ACCESS_USER_IDS='',
           MARKET_PREPARATION_ENABLED='false', MARKET_SETTLEMENT_ENABLED='false')
env['SPRING_APPLICATION_JSON'] = json.dumps({'spring.datasource.url': env['DB_URL'], 'spring.datasource.username': 'root', 'spring.datasource.password': '', 'boxbox.auth.jwt-secret': env['AUTH_JWT_SECRET'], 'boxbox.auth.extended-access-user-ids': '', 'boxbox.market.preparation.enabled': False, 'boxbox.market.settlement.enabled': False, 'boxbox.race.session-sync.enabled': False})
logs = BE/'build/verification'
with (logs/'issue-61-server.log').open('w',encoding='utf-8') as out, (logs/'issue-61-server-error.log').open('w',encoding='utf-8') as err:
    server = subprocess.Popen(['C:/Program Files/Java/jdk-21/bin/java.exe','-jar',str(BE/'build/libs/boxbox-0.0.1-SNAPSHOT.jar'),
                               '--server.address=127.0.0.1','--server.port=0','--boxbox.race.session-sync.enabled=false'],cwd=BE,env=env,
                              stdout=out,stderr=err,creationflags=subprocess.CREATE_NO_WINDOW)
    try:
        deadline = time.monotonic()+50
        while True:
            ports = re.findall(r'Tomcat started on port (\d+)',(logs/'issue-61-server.log').read_text(encoding='utf-8'))
            if ports: port = int(ports[-1]); break
            if server.poll() is not None or time.monotonic()>deadline: raise RuntimeError('Server startup failed; inspect log')
            time.sleep(.2)
        tokens=[]; users=[]
        for login,nickname in [('korean61_owner','한글확인회원'),('korean61_peer','한글확인타인')]:
            signup=request('/api/auth/signup',201,method='POST',body=dict(loginId=login,password='verify1234',nickname=nickname))
            users.append(signup['data']['userId'])
            tokens.append(request('/api/auth/login',method='POST',body=dict(loginId=login,password='verify1234'))['data']['accessToken'])
        token, peer = tokens; user = users[0]
        # The expanded scope uses the same real server and isolated reference IDs.
        sql(f"UPDATE races SET status='FINISHED'; UPDATE races SET status='SCHEDULED',weekend_starts_at=UTC_TIMESTAMP()+INTERVAL 1 HOUR,weekend_ends_at=UTC_TIMESTAMP()+INTERVAL 3 DAY WHERE id={race}", fresh)
        sql(f"INSERT INTO race_sessions(race_id,type,session_number,name,starts_at) VALUES({race},'PRACTICE',1,'Practice 1',UTC_TIMESTAMP()+INTERVAL 1 HOUR),({race},'RACE',1,'Race',UTC_TIMESTAMP()+INTERVAL 2 DAY)",fresh)
        sql(f"INSERT INTO market_rounds(id,race_id,season_year,status,open_at,close_at,publish_at,rule_version,rule_snapshot) VALUES(61,{race},2026,'OPEN',UTC_TIMESTAMP()-INTERVAL 1 DAY,UTC_TIMESTAMP()+INTERVAL 2 DAY,UTC_TIMESTAMP()+INTERVAL 3 DAY,'verify-61','{{}}'); INSERT INTO market_stocks(id,round_id,season_year,driver_id,team_id,base_rank,starting_price,current_price,theoretical_price) VALUES(61,61,2026,{driver},{team},1,50000,60000,60000); INSERT INTO positions(user_id,stock_id,quantity,total_cost) VALUES({user},61,2,100000)",fresh)
        ref=request('/api/reference-data')['data']
        check('reference teams and drivers use Korean with original names',next(t for t in ref['teams'] if t['teamId']==team)['name']=='페라리' and next(d for d in ref['drivers'] if d['driverId']==driver)['nameEn']=='Charles Leclerc')
        races=request('/api/races?season=2026&size=100')['data']['items']
        check('race schedule uses Korean and preserves English',next(r for r in races if r['raceId']==race)['name']=='라스베이거스 그랑프리' and next(r for r in races if r['raceId']==race)['nameEn']=='Las Vegas Grand Prix')
        detail=request(f'/api/races/{race}')['data']
        check('race detail and typed session names localized',detail['name']=='라스베이거스 그랑프리' and detail['sessions'][0]['name']=='프리 프랙티스 1' and detail['sessions'][0]['nameEn']=='Practice 1' and detail['sessions'][1]['name']=='본선')
        home=request('/api/home')['data']
        check('home reuses localized race session room and market names',home['nextRace']['name']=='라스베이거스 그랑프리' and home['nextSession']['name']=='프리 프랙티스 1' and home['liveChatRoom']['raceName']=='라스베이거스 그랑프리' and home['marketRound']['raceName']=='라스베이거스 그랑프리')
        current=request('/api/market/rounds/current')['data']['round']
        check('current market round uses Korean race name',current['roundId']=='61' and current['raceName']=='라스베이거스 그랑프리')
        check('market detail uses Korean race name',request('/api/market/rounds/61')['data']['raceName']=='라스베이거스 그랑프리')
        stock=request(f'/api/market/rounds/61/stocks?teamId={team}')['data']['items'][0]
        check('stock list keeps IDs prices and localized driver team',stock['driver']['driverId']==driver and stock['driver']['name']=='샤를 르클레르' and stock['driver']['teamName']=='페라리' and stock['currentPrice']==60000)
        stock_detail=request(f'/api/market/rounds/61/stocks/{driver}')['data']
        check('stock detail shares localized driver',stock_detail['driver']['name']=='샤를 르클레르')
        portfolio=request('/api/me/portfolio?roundId=61',token=token)['data']
        check('portfolio localizes positions without changing valuation',portfolio['positions'][0]['driver']['name']=='샤를 르클레르' and portfolio['positions'][0]['driver']['teamName']=='페라리' and portfolio['positions'][0]['valuation']==120000)
        sql(f"INSERT INTO user_settlements(round_id,user_id,round_start_assets,ending_assets,round_pnl,settlement_proceeds,settlement_pnl,return_rate,status,joined_at,completed_at,published_at,rule_snapshot) VALUES(61,{user},1000000,1020000,20000,120000,20000,2,'COMPLETED',UTC_TIMESTAMP()-INTERVAL 1 DAY,UTC_TIMESTAMP()-INTERVAL 1 HOUR,UTC_TIMESTAMP()-INTERVAL 1 HOUR,'{{}}')",fresh)
        settlements=request('/api/me/settlements?season=2026',token=token)['data']['items']
        check('settlement list Korean name and stored amounts preserved',settlements[0]['raceName']=='라스베이거스 그랑프리' and settlements[0]['roundPnl']==20000)
        check('published settlement detail remains accessible',request('/api/me/settlements/61',token=token)['data']['endingAssets']==1020000)
        request('/api/races/999999',404)
        check('empty race filter returns empty list',request('/api/races?season=2099')['data']['items']==[])
        request('/api/me',401)
        profile = request('/api/me',token=token,method='PATCH',body=dict(favoriteTeamId=team,favoriteDriverId=driver))['data']
        check('profile PATCH Korean favorite names', profile['favoriteTeam']['name']=='페라리' and profile['favoriteDriver']['name']=='샤를 르클레르' and profile['favoriteDriver']['teamName']=='페라리')
        profile = request('/api/me',token=token)['data']
        check('English favorites and IDs preserved', profile['favoriteTeam']['nameEn']=='Ferrari' and profile['favoriteDriver']['nameEn']=='Charles Leclerc' and profile['favoriteTeam']['teamId']==team and profile['favoriteDriver']['driverId']==driver)
        garage = request(f'/api/users/{user}/garage',token=peer)['data']
        check('public garage shares localized profile and privacy policy', garage['profile']['favoriteDriver']['name']=='샤를 르클레르' and garage['profile']['favoriteDriver']['nameEn']=='Charles Leclerc' and 'showMarketAssets' not in garage['profile'] and garage['marketSummary'] is None)
        board_data = request(f'/api/boards?teamId={team}')['data']['items'][0]
        check('board name is derived without rewriting English', board_data['boardId']==board and board_data['name']=='페라리 게시판' and 'nameKo' not in board_data)
        tags = request(f'/api/boards/{board}/tags')['data']['items']
        check('team and driver tags share reference translations', any(t['name']=='페라리' for t in tags) and next(t for t in tags if t['tagId']==driver_tag)['name']=='샤를 르클레르')
        free_board = sql("SELECT id FROM boards WHERE code='FREE'", fresh)
        check('topic tags keep their original display field', all('name' in t and 'nameKo' not in t for t in request(f'/api/boards/{free_board}/tags')['data']['items']))
        post = request(f'/api/boards/{board}/posts',201,token=token,method='POST',body=dict(title='한글 이름 확인',content='태그 표시 검증',tagIds=[driver_tag]))['data']['postId']
        for path in [f'/api/posts/{post}',f'/api/boards/{board}/posts','/api/me/posts?filter=AUTHORED']:
            data=request(path,token=token)['data']
            item=data if 'tags' in data else data['items'][0]
            check(path+' localized tag',item['tags'][0]['name']=='샤를 르클레르' and 'nameKo' not in item['tags'][0] and item['tags'][0]['tagId']==driver_tag)
        from urllib.parse import quote
        found=request('/api/posts?q='+quote('르클레르'))['data']
        check('Korean tag is searchable and search includes Korean board name',found['totalCount']==1 and found['items'][0]['boardName']=='페라리 게시판' and 'boardNameKo' not in found['items'][0])
        featured=request('/api/home')['data']['featuredPosts'][0]
        check('home featured board and tags localized',featured['boardName']=='페라리 게시판' and featured['tags'][0]['name']=='샤를 르클레르')
        check('empty search stays an empty list',request('/api/posts?q=unknown-61-empty')['data']['items']==[])
        null_race = request(f'/api/chat-rooms/{null_room}')['data']
        check('untranslated room falls back to original English name',null_race['raceName']=='Qatar Grand Prix' and null_race['raceNameEn']=='Qatar Grand Prix' and 'raceNameKo' not in null_race)
        live=request(f'/api/chat-rooms/{room}')['data']
        check('room has localized and original race name',live['raceName']=='라스베이거스 그랑프리' and live['raceNameEn']=='Las Vegas Grand Prix')
        rooms=request('/api/chat-rooms?season=2026&status=LIVE')['data']['items']
        check('filtered rooms retain Korean names',len(rooms)==1 and rooms[0]['raceName']==live['raceName'] and rooms[0]['raceNameEn']==live['raceNameEn'])
        check('empty season stays empty',request('/api/chat-rooms?season=2099')['data']['items']==[])
        stamps=request(f'/api/users/{user}/race-stamps?season=2026',token=token)['data']
        check('stamps carry Korean names even without participation',len(stamps['items'])==23 and stamps['watchedCount']==0 and next(s for s in stamps['items'] if s['raceId']==race)['raceName']==live['raceName'])
        check('untranslated stamp falls back to original name',next(s for s in stamps['items'] if s['raceId']==untranslated_race)['raceName']=='Qatar Grand Prix')
        sql(f'UPDATE teams SET name_ko=NULL WHERE id={team}',fresh)
        null_profile=request('/api/me',token=token)['data']
        check('untranslated favorite and team keep non-null original names',null_profile['favoriteTeam']['name']=='Ferrari' and null_profile['favoriteDriver']['teamName']=='Ferrari' and null_profile['favoriteTeam']['nameEn']=='Ferrari')
        check('untranslated board uses its original stored name',request(f'/api/boards?teamId={team}')['data']['items'][0]['name']=='Ferrari 게시판')
        sql(f"UPDATE drivers SET name_ko=' ' WHERE id={driver}",fresh)
        ref=request('/api/reference-data')['data']
        check('reference null and blank translations fall back to original',next(t for t in ref['teams'] if t['teamId']==team)['name']=='Ferrari' and next(d for d in ref['drivers'] if d['driverId']==driver)['name']=='Charles Leclerc')
        check('stock detail uses original names after translation removed',request(f'/api/market/rounds/61/stocks/{driver}')['data']['driver']['name']=='Charles Leclerc')
        request('/api/chat-rooms?status=BAD',400)
        request('/api/chat-rooms/999999',404)
        request('/api/boards/999999/tags',404)
        check('stored boards/tags and relations unchanged',all(radio_state[t]==sql('SELECT * FROM '+t+' ORDER BY id',fresh) for t in radio_state))
        check('reads created no chat participants or stamps',sql('SELECT COUNT(*) FROM chat_participants',fresh)=='0')
        result=dict(verifiedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),databaseType='isolated MySQL',mysqlVersion=identity[2],databases=[legacy,fresh],
                    checksPassed=len(checks),httpRequests=len(responses),checks=checks,responses=responses)
        args.evidence.parent.mkdir(parents=True,exist_ok=True)
        args.evidence.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({k:result[k] for k in ['databaseType','mysqlVersion','checksPassed','httpRequests','databases']}))
    finally:
        server.terminate()
        try: server.wait(timeout=15)
        except subprocess.TimeoutExpired: server.kill(); server.wait(timeout=5)
