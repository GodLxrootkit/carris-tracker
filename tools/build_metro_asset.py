import argparse, csv, io, json, os, zipfile, math

HERE=os.path.dirname(os.path.abspath(__file__))
RAW_DIR=os.path.join(os.path.dirname(HERE),'app','src','main','res','raw')

# Official Metro de Lisboa line colours; the GTFS routes.txt has no usable route_color.
LINE_COLORS={'A':'#1F5FA9','B':'#FFD100','C':'#00A651','D':'#E4002B'}

def read(zf,name):
    with zf.open(name) as raw:
        text=io.TextIOWrapper(raw,encoding='utf-8-sig',newline='').read()
    return list(csv.DictReader(text.splitlines()))

def minutes(t):
    h,m,_=t.split(':'); return int(h)*60+int(m)

def dist(lat1,lon1,lat2,lon2):
    R=6371000
    dlat=math.radians(lat2-lat1); dlon=math.radians(lon2-lon1)
    a=math.sin(dlat/2)**2+math.cos(math.radians(lat1))*math.cos(math.radians(lat2))*math.sin(dlon/2)**2
    return 2*R*math.asin(math.sqrt(a))/1000.0

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('gtfs_zip', nargs='?', default='metro_gtfs.zip')
    args=ap.parse_args()
    z=zipfile.ZipFile(args.gtfs_zip)
    routes={r['route_id']:r for r in read(z,'routes.txt')}
    stops={s['stop_id']:s for s in read(z,'stops.txt')}
    trips=read(z,'trips.txt')
    stop_times=read(z,'stop_times.txt')

    # group stop times by trip
    by_trip={}
    for r in stop_times:
        by_trip.setdefault(r['trip_id'],[]).append(r)
    for t in by_trip.values():
        t.sort(key=lambda r:int(r['stop_sequence']))

    # weekday/weekend timetable per service_id. Day names rather than exact dates: the asset is
    # bundled in the APK and only refreshed at release time, so date ranges would expire and
    # silently leave the Metro with no departures.
    day_flags=['monday','tuesday','wednesday','thursday','friday','saturday','sunday']
    service_days={}
    for c in read(z,'calendar.txt'):
        days=tuple(d for d in day_flags if c[d]=='1')
        service_days[c['service_id']]=() if len(days)==7 else days

    # group trips by route+headsign+weekdays, then by their exact stop sequence. One headsign
    # can have short turns or a variant that skips a station (the Azul line's Praça de Espanha),
    # so a single representative trip per headsign silently dropped stops and all departures.
    patterns={}
    for tr in trips:
        seq=tuple(s['stop_id'] for s in by_trip.get(tr['trip_id'],[]))
        if not seq: continue
        days=service_days.get(tr['service_id'],())
        patterns.setdefault((tr['route_id'],tr['trip_headsign'],days,seq),[]).append(tr)

    variants={}
    for (route_id,headsign,days,seq),trs in patterns.items():
        variants.setdefault((route_id,headsign),[]).append((days,seq,trs))

    # build pattern list, most-used variant first so the primary pattern id stays stable
    pat_list=[]
    for (route_id,headsign),seqs in variants.items():
        seqs.sort(key=lambda v:(-len(v[2]),v[0],v[1]))
        base=f"{route_id}_{headsign}".replace(' ','_')
        for index,(days,seq,trs) in enumerate(seqs):
            rep=trs[0]
            sts=by_trip.get(rep['trip_id'],[])
            if not sts: continue
            p_stops=[]
            km=0.0
            prev=None
            for s in sts:
                st=stops[s['stop_id']]
                lat=float(st['stop_lat']); lon=float(st['stop_lon'])
                if prev is not None:
                    km+=dist(prev[0],prev[1],lat,lon)
                p_stops.append({'stopId':s['stop_id'],'sequence':int(s['stop_sequence']),'km':round(km,3)})
                prev=(lat,lon)
            # times per stop, from this variant's trips only
            times_by_stop={}
            for tr in trs:
                for s in by_trip.get(tr['trip_id'],[]):
                    times_by_stop.setdefault(s['stop_id'],set()).add(minutes(s['arrival_time']))
            for ps in p_stops:
                ps['times']=sorted(times_by_stop.get(ps['stopId'],[]))
            pat_id=base if index==0 else f"{base}_{index+1}"
            pat_list.append({
                'id':pat_id,
                'lineId':route_id,
                'headsign':headsign,
                'shapeId':None,
                'validOn':list(days),
                'stops':p_stops
            })

    # lines
    line_list=[]
    for rid,r in routes.items():
        line_list.append({
            'id':rid,
            'shortName':r['route_short_name'] or rid,
            'longName':r['route_long_name'] or r['route_desc'] or '',
            'color':LINE_COLORS.get(rid,'#1F5FA9'),
            'patternIds':[p['id'] for p in pat_list if p['lineId']==rid]
        })

    # stops list (all)
    stop_list=[]
    for sid,s in stops.items():
        stop_list.append({
            'id':sid,
            'name':s['stop_name'],
            'lat':float(s['stop_lat']),
            'lon':float(s['stop_lon']),
            'lineIds':[]  # fill below
        })
    # map stop->lines
    stop_lines={}
    for p in pat_list:
        for ps in p['stops']:
            stop_lines.setdefault(ps['stopId'],set()).add(p['lineId'])
    for st in stop_list:
        st['lineIds']=sorted(stop_lines.get(st['id'],[]))

    # shapes empty
    shapes={}

    os.makedirs(RAW_DIR,exist_ok=True)
    with open(os.path.join(RAW_DIR,'metro_lines.json'),'w',encoding='utf-8') as f:
        json.dump({'feedVersion':'cp','feedStartDate':'','feedEndDate':'','source':'cp','excluded':[],'routes':line_list,'patterns':pat_list,'stops':stop_list},f,ensure_ascii=False)
    # times file: per stop pattern times
    times_obj={}
    for p in pat_list:
        for ps in p['stops']:
            times_obj.setdefault(ps['stopId'],{}).setdefault(p['id'],[]).extend(ps['times'])
    # dedup
    for sid in times_obj:
        for pid in times_obj[sid]:
            times_obj[sid][pid]=sorted(set(times_obj[sid][pid]))
    with open(os.path.join(RAW_DIR,'metro_times.json'),'w',encoding='utf-8') as f:
        json.dump({'stops':times_obj},f,ensure_ascii=False)
    with open(os.path.join(RAW_DIR,'metro_shapes.json'),'w',encoding='utf-8') as f:
        json.dump({'shapes':shapes},f,ensure_ascii=False)
    print('done', len(line_list), len(pat_list), len(stop_list))

if __name__=='__main__':
    main()
