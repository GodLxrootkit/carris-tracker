import argparse, csv, io, json, os, zipfile, math, re, unicodedata

HERE=os.path.dirname(os.path.abspath(__file__))
RAW_DIR=os.path.join(os.path.dirname(HERE),'app','src','main','res','raw')
WEEKDAYS=['monday','tuesday','wednesday','thursday','friday','saturday','sunday']

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

def norm(name):
    s=unicodedata.normalize('NFKD',name).encode('ascii','ignore').decode().lower()
    return ' '.join(re.sub(r'[^a-z0-9]+',' ',s).split())

def slug(s):
    s=unicodedata.normalize('NFKD',s).encode('ascii','ignore').decode().lower()
    return re.sub(r'[^a-z0-9]+','_',s).strip('_')

def service_calendar(zf):
    cal={}
    names=set(zf.namelist())
    if 'calendar.txt' in names:
        for r in read(zf,'calendar.txt'):
            cal[r['service_id']]=[r['start_date'],r['end_date'],
                                  [d for d in WEEKDAYS if r.get(d)=='1'],set(),set()]
    if 'calendar_dates.txt' in names:
        for r in read(zf,'calendar_dates.txt'):
            c=cal.setdefault(r['service_id'],['','',[],set(),set()])
            (c[4] if r['exception_type']=='1' else c[3]).add(r['date'])
    return {sid:(a,b,tuple(days),tuple(sorted(removed)),tuple(sorted(added)))
            for sid,(a,b,days,removed,added) in cal.items()}

def build_patterns(zf,stops,stop_map,id_prefix,line_prefix):
    trips=read(zf,'trips.txt')
    seq_by_trip={}
    for r in read(zf,'stop_times.txt'):
        seq_by_trip.setdefault(r['trip_id'],[]).append(r)
    for rows in seq_by_trip.values():
        rows.sort(key=lambda r:int(r['stop_sequence']))
    cal=service_calendar(zf)

    groups={}
    for tr in trips:
        sts=seq_by_trip.get(tr['trip_id'],[])
        if not sts: continue
        seq=tuple(s['stop_id'] for s in sts)
        descriptor=cal.get(tr['service_id'],('','',tuple(WEEKDAYS),(),()))
        key=(tr['route_id'],tr['trip_headsign'],seq,descriptor)
        groups.setdefault(key,[]).append(tr)

    pats=[]
    seq_index={}
    for (route_id,headsign,seq,descriptor),trs in groups.items():
        base=(route_id,headsign)
        index=seq_index.get(base,0)
        seq_index[base]=index+1
        p_stops=[]
        km=0.0
        prev=None
        for s in seq_by_trip[trs[0]['trip_id']]:
            st=stops[s['stop_id']]
            lat=float(st['stop_lat']); lon=float(st['stop_lon'])
            if prev is not None:
                km+=dist(prev[0],prev[1],lat,lon)
            p_stops.append({'stopId':stop_map[s['stop_id']],'sequence':int(s['stop_sequence']),'km':round(km,3)})
            prev=(lat,lon)
        times_by_stop={}
        for tr in trs:
            for s in seq_by_trip.get(tr['trip_id'],[]):
                times_by_stop.setdefault(stop_map[s['stop_id']],set()).add(minutes(s['arrival_time']))
        for ps in p_stops:
            ps['times']=sorted(times_by_stop.get(ps['stopId'],[]))
        pat_id=f"{id_prefix}{slug(route_id)}_{slug(headsign)}_{index}"
        # A service that only exists in calendar_dates has no calendar row. Put its added
        # dates in validOn; otherwise the empty bounds and weekdays would mean "every day".
        date_only=not descriptor[0] and not descriptor[1]
        pats.append({
            'id':pat_id,
            'lineId':f"{line_prefix}{route_id}",
            'headsign':headsign,
            'shapeId':None,
            'validOn':list(descriptor[4]) if date_only else [],
            'validFrom':descriptor[0],
            'validTo':descriptor[1],
            'validDays':list(descriptor[2]),
            'validExcept':list(descriptor[3]),
            'validAdd':list(descriptor[4]),
            'dateOnly':date_only,
            'stops':p_stops
        })
    return pats

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('gtfs_zip', nargs='?', default='cp_gtfs.zip')
    ap.add_argument('fertagus_zip', nargs='?', default=None,
                    help='optional Fertagus GTFS; merged into the CP asset')
    args=ap.parse_args()
    z=zipfile.ZipFile(args.gtfs_zip)
    routes={r['route_id']:r for r in read(z,'routes.txt')}
    stops={s['stop_id']:s for s in read(z,'stops.txt')}

    stop_list=[]
    cp_stop_map={}
    for sid,s in stops.items():
        stop_list.append({
            'id':sid,
            'name':s['stop_name'],
            'lat':float(s['stop_lat']),
            'lon':float(s['stop_lon']),
            'lineIds':[]
        })
        cp_stop_map[sid]=sid

    pat_list=build_patterns(z,stops,cp_stop_map,'','')

    froutes={}
    if args.fertagus_zip:
        fz=zipfile.ZipFile(args.fertagus_zip)
        froutes={r['route_id']:r for r in read(fz,'routes.txt')}
        fstops={s['stop_id']:s for s in read(fz,'stops.txt')}

        # shortcut: match shared stations by normalized name and distance; switch to NAP
        # station ids if the two feeds ever start publishing them.
        cp_by_name={}
        for st in stop_list:
            cp_by_name.setdefault(norm(st['name']),[]).append(st)
        fmap={}
        for sid,s in fstops.items():
            lat=float(s['stop_lat']); lon=float(s['stop_lon'])
            near=cp_by_name.get(norm(s['stop_name']),[])
            match=min(near,key=lambda c:dist(lat,lon,c['lat'],c['lon']),default=None)
            if match is not None and dist(lat,lon,match['lat'],match['lon'])<2.0:
                fmap[sid]=match['id']
            else:
                fmap[sid]=sid
                stop_list.append({
                    'id':sid,
                    'name':s['stop_name'],
                    'lat':lat,
                    'lon':lon,
                    'lineIds':[]
                })
        pat_list.extend(build_patterns(fz,fstops,fmap,'fert_','fert_'))

    # The Badajoz stop is absent from the CP stops feed, but its id is in the route id.
    missing_stops={'71_37606':'Badajoz'}

    # Derive a readable origin -> destination name from the stop ids embedded in route_id.
    def route_name(rid):
        ids=[sid for sid in stops if sid in rid]
        names=[stops[sid]['stop_name'] for sid in ids]
        if len(names)<2:
            names += [missing_stops[sid] for sid in rid.split('-')[1:]
                      if sid in missing_stops and sid not in stops]
        if len(names)>=2:
            return f"{names[0]} - {names[-1]}"
        return rid

    colors={'IC':'#C61D23','IR':'#1F5FA9','R':'#2E7D32','AP':'#6A1B9A','ALFA':'#00838F'}
    line_list=[]
    for rid,r in routes.items():
        sn=r['route_short_name'] or rid
        line_list.append({
            'id':rid,
            'shortName':sn,
            'longName':route_name(rid),
            'color':colors.get(sn,'#1F5FA9'),
            'patternIds':[p['id'] for p in pat_list if p['lineId']==rid]
        })
    for rid,r in froutes.items():
        line_id=f"fert_{rid}"
        pids=[p['id'] for p in pat_list if p['lineId']==line_id]
        if not pids: continue
        color=r.get('route_color','C74F4F').lstrip('#')
        line_list.append({
            'id':line_id,
            'shortName':r.get('route_short_name') or 'Fertagus',
            'longName':r.get('route_long_name') or rid,
            'color':f"#{color}",
            'patternIds':pids
        })

    stop_lines={}
    for p in pat_list:
        for ps in p['stops']:
            stop_lines.setdefault(ps['stopId'],set()).add(p['lineId'])
    for st in stop_list:
        st['lineIds']=sorted(stop_lines.get(st['id'],[]))

    shapes={}

    os.makedirs(RAW_DIR,exist_ok=True)
    with open(os.path.join(RAW_DIR,'cp_lines.json'),'w',encoding='utf-8') as f:
        json.dump({'feedVersion':'cp','feedStartDate':'','feedEndDate':'','source':'cp','excluded':[],'routes':line_list,'patterns':pat_list,'stops':stop_list},f,ensure_ascii=False)
    times_obj={}
    for p in pat_list:
        for ps in p['stops']:
            times_obj.setdefault(ps['stopId'],{}).setdefault(p['id'],[]).extend(ps['times'])
    for sid in times_obj:
        for pid in times_obj[sid]:
            times_obj[sid][pid]=sorted(set(times_obj[sid][pid]))
    with open(os.path.join(RAW_DIR,'cp_times.json'),'w',encoding='utf-8') as f:
        json.dump({'stops':times_obj},f,ensure_ascii=False)
    with open(os.path.join(RAW_DIR,'cp_shapes.json'),'w',encoding='utf-8') as f:
        json.dump({'shapes':shapes},f,ensure_ascii=False)
    print('done', len(line_list), len(pat_list), len(stop_list))

if __name__=='__main__':
    main()
