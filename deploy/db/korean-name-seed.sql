-- #61: 기존 기준정보의 한국어 표시명만 채운다. 팀/선수/경기를 새로 생성하지 않는다.
-- 팀/선수 코드는 제공된 기준정보 시드 기준이다. 경기 라운드는 해당 시드의 2026 라운드다.
-- 시즌+라운드+external_key를 함께 확인해 다른 경기의 이름을 잘못 채우지 않는다.
-- 이미 입력된 한국어는 보존한다. 표기 수정이 필요하면 별도 검토한 UPDATE로 변경한다.
SET NAMES utf8mb4;
START TRANSACTION;

UPDATE teams SET name_ko = CASE code
  WHEN 'alpine' THEN '알핀'
  WHEN 'aston_martin' THEN '애스턴 마틴'
  WHEN 'audi' THEN '아우디'
  WHEN 'cadillac' THEN '캐딜락'
  WHEN 'ferrari' THEN '페라리'
  WHEN 'haas_f1_team' THEN '하스 F1 팀'
  WHEN 'mclaren' THEN '맥라렌'
  WHEN 'mercedes' THEN '메르세데스'
  WHEN 'racing_bulls' THEN '레이싱 불스'
  WHEN 'red_bull_racing' THEN '레드불 레이싱'
  WHEN 'williams' THEN '윌리엄스'
  ELSE name_ko END
WHERE name_ko IS NULL AND code IN ('alpine','aston_martin','audi','cadillac','ferrari','haas_f1_team','mclaren','mercedes','racing_bulls','red_bull_racing','williams');

UPDATE drivers SET name_ko = CASE code
  WHEN 'ALB' THEN '알렉산더 알본'
  WHEN 'ALO' THEN '페르난도 알론소'
  WHEN 'ANT' THEN '키미 안토넬리'
  WHEN 'BEA' THEN '올리버 베어먼'
  WHEN 'BOR' THEN '가브리엘 보르톨레토'
  WHEN 'BOT' THEN '발테리 보타스'
  WHEN 'CAM' THEN '하파엘 카마라'
  WHEN 'COL' THEN '프랑코 콜라핀토'
  WHEN 'GAS' THEN '피에르 가슬리'
  WHEN 'HAD' THEN '아이작 하자르'
  WHEN 'HAM' THEN '루이스 해밀턴'
  WHEN 'HIR' THEN '히라카와 료'
  WHEN 'HUL' THEN '니코 휠켄베르크'
  WHEN 'LAW' THEN '리암 로슨'
  WHEN 'LEC' THEN '샤를 르클레르'
  WHEN 'LIN' THEN '아르비드 린드블라드'
  WHEN 'NOR' THEN '랜도 노리스'
  WHEN 'OCO' THEN '에스테반 오콘'
  WHEN 'PER' THEN '세르히오 페레스'
  WHEN 'PIA' THEN '오스카 피아스트리'
  WHEN 'RUS' THEN '조지 러셀'
  WHEN 'SAI' THEN '카를로스 사인츠'
  WHEN 'STR' THEN '랜스 스트롤'
  WHEN 'TSU' THEN '츠노다 유키'
  WHEN 'VER' THEN '막스 베르스타펜'
  ELSE name_ko END
WHERE name_ko IS NULL AND code IN ('ALB','ALO','ANT','BEA','BOR','BOT','CAM','COL','GAS','HAD','HAM','HIR','HUL','LAW','LEC','LIN','NOR','OCO','PER','PIA','RUS','SAI','STR','TSU','VER');

UPDATE races r JOIN (
  SELECT 1 AS round_number,'1279' AS external_key,'호주 그랑프리' AS name_ko
  UNION ALL SELECT 2,'1280','중국 그랑프리'
  UNION ALL SELECT 3,'1281','일본 그랑프리'
  UNION ALL SELECT 4,'1284','마이애미 그랑프리'
  UNION ALL SELECT 5,'1285','캐나다 그랑프리'
  UNION ALL SELECT 6,'1286','모나코 그랑프리'
  UNION ALL SELECT 7,'1287','바르셀로나 그랑프리'
  UNION ALL SELECT 8,'1288','오스트리아 그랑프리'
  UNION ALL SELECT 9,'1289','영국 그랑프리'
  UNION ALL SELECT 10,'1290','벨기에 그랑프리'
  UNION ALL SELECT 11,'1291','헝가리 그랑프리'
  UNION ALL SELECT 12,'1292','네덜란드 그랑프리'
  UNION ALL SELECT 13,'1293','이탈리아 그랑프리'
  UNION ALL SELECT 14,'1294','스페인 그랑프리'
  UNION ALL SELECT 15,'1295','아제르바이잔 그랑프리'
  UNION ALL SELECT 16,'1308','바레인 그랑프리'
  UNION ALL SELECT 17,'1296','싱가포르 그랑프리'
  UNION ALL SELECT 18,'1297','미국 그랑프리'
  UNION ALL SELECT 19,'1298','멕시코시티 그랑프리'
  UNION ALL SELECT 20,'1299','상파울루 그랑프리'
  UNION ALL SELECT 21,'1300','라스베이거스 그랑프리'
  UNION ALL SELECT 22,'1301','카타르 그랑프리'
  UNION ALL SELECT 23,'1302','아부다비 그랑프리'
) names ON r.season_year=2026 AND r.round_number=names.round_number AND r.external_key=names.external_key
SET r.name_ko=names.name_ko WHERE r.name_ko IS NULL;

COMMIT;
