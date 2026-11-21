
-- Drop this partial index before the mass update below.
drop index if exists FullDataRegenerateIndex;
--batch--

-- generate full detail LODs built before block models again
update FullData set Regenerate = 1 where DetailLevel = 0 and DataFormatVersion < 3;
--batch--

-- rebuild the index in one pass now that the update is done
create index FullDataRegenerateIndex on FullData (Regenerate) where Regenerate = 1;
