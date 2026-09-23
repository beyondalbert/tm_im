import io, sys, yaml

class Loader(yaml.SafeLoader):
    pass

def unknown(loader, suffix, node):
    if isinstance(node, yaml.MappingNode):
        return loader.construct_mapping(node, deep=True)
    if isinstance(node, yaml.SequenceNode):
        return loader.construct_sequence(node, deep=True)
    return loader.construct_scalar(node)

Loader.add_multi_constructor("!", unknown)

ok = True
for p in [r"deploy\conf\sharding.yaml.example", r"deploy\conf\application-external.yml.example"]:
    txt = io.open(p, encoding="utf-8").read()
    try:
        d = yaml.load(txt, Loader=Loader)
        print("OK   {}".format(p))
        print("     top-level keys:", list(d.keys()))
        if "rules" in d:
            for r in d["rules"]:
                print("     rule keys:", list(r.keys()))
        if "dataSources" in d:
            ds = d["dataSources"]["ds_0"]
            print("     ds_0 keys:", list(ds.keys()))
            print("     jdbcUrl head:", ds["jdbcUrl"][:50], "...")
        if "tm" in d:
            print("     tm sections:", list(d["tm"].keys()))
    except Exception as e:
        ok = False
        print("FAIL {}: {}".format(p, e))

sys.exit(0 if ok else 1)
