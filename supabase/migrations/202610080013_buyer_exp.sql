-- 202610080013_buyer_exp.sql
--
-- Sistem Level & EXP pembeli.
-- Aturan:
--   * 10 EXP per Rp10.000 belanja, dibulatkan ke bawah (Rp25.000 = 20 EXP).
--   * EXP hanya diberikan saat order menjadi `completed` (bukan saat dibuat,
--     dibayar, dibatalkan). Pembatalan setelah completed mengembalikan EXP.
--   * Satu order hanya bisa diberi reward sekali (unique index parsial +
--     ON CONFLICT DO NOTHING + cek di fungsi).
--   * Semua tulis dilakukan trigger SECURITY DEFINER; client hanya boleh
--     SELECT baris miliknya sendiri. Level dihitung dari saldo di aplikasi.
--
-- CARA PAKAI: Supabase Dashboard -> SQL Editor -> paste seluruh file -> Run.

begin;

-- ============ 1) Saldo EXP di profiles (reuse tabel profil) ============

alter table public.profiles
    add column if not exists exp_balance integer not null default 0;

-- ============ 2) Buku besar EXP (riwayat per order) ============

create table if not exists public.exp_ledger (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    order_id uuid references public.orders(id) on delete set null,
    pickup_code text not null default '',
    exp integer not null check (exp <> 0),
    reason text not null default '',
    balance_after integer not null default 0,
    created_at timestamptz not null default now()
);

-- Satu order hanya boleh punya SATU baris reward positif (idempotensi).
create unique index if not exists exp_ledger_one_reward_per_order
    on public.exp_ledger(order_id) where exp > 0 and order_id is not null;

create index if not exists exp_ledger_user_idx
    on public.exp_ledger(user_id, created_at desc);

alter table public.exp_ledger enable row level security;

-- Pembeli hanya baca riwayat miliknya. TANPA policy insert/update/delete:
-- client tidak bisa menulis/mengubah/menghapus EXP sama sekali.
drop policy if exists "Buyers read own exp ledger" on public.exp_ledger;
create policy "Buyers read own exp ledger" on public.exp_ledger
for select to authenticated
using (auth.uid() = user_id);

-- ============ 3) Trigger award + reversal ============

create or replace function public.settle_order_exp()
returns trigger
language plpgsql
security definer
set search_path to ''
as $function$
declare
    award integer;
    inserted_id uuid;
begin
    -- Masuk completed: beri reward (sekali saja per order).
    if old.status is distinct from 'completed'
       and new.status = 'completed' then
        award := (new.total_idr / 10000) * 10;
        if award > 0 then
            insert into public.exp_ledger (user_id, order_id, pickup_code, exp, reason)
            values (
                new.user_id, new.id, coalesce(new.pickup_code, ''),
                award, 'Order selesai ' || coalesce(new.pickup_code, '')
            )
            on conflict (order_id) where exp > 0 and order_id is not null
            do nothing
            returning id into inserted_id;
            if inserted_id is not null then
                update public.profiles
                   set exp_balance = exp_balance + award
                 where id = new.user_id;
                update public.exp_ledger
                   set balance_after = (select exp_balance from public.profiles where id = new.user_id)
                 where id = inserted_id;
            end if;
        end if;
        return new;
    end if;

    -- Keluar dari completed (dibatalkan): kembalikan EXP yang pernah diberi,
    -- sekali saja (abaikan bila belum pernah diberi / sudah dikembalikan).
    if old.status = 'completed'
       and new.status is distinct from 'completed' then
        select coalesce(sum(exp), 0) into award
          from public.exp_ledger
         where order_id = new.id and exp > 0;
        if award > 0
           and not exists (
                select 1 from public.exp_ledger
                 where order_id = new.id and exp < 0
           ) then
            insert into public.exp_ledger (user_id, order_id, pickup_code, exp, reason)
            values (
                new.user_id, new.id, coalesce(new.pickup_code, ''),
                -award, 'Penyesuaian refund ' || coalesce(new.pickup_code, '')
            );
            update public.profiles
               set exp_balance = greatest(0, exp_balance - award)
             where id = new.user_id;
            update public.exp_ledger
               set balance_after = (select exp_balance from public.profiles where id = new.user_id)
             where user_id = new.user_id and order_id = new.id and exp < 0;
        end if;
        return new;
    end if;

    return new;
end;
$function$;

drop trigger if exists orders_settle_exp on public.orders;
create trigger orders_settle_exp
after update of status on public.orders
for each row execute function public.settle_order_exp();

-- ============ 4) Backfill: order completed lama yang belum ada reward ============

insert into public.exp_ledger (user_id, order_id, pickup_code, exp, reason)
select o.user_id, o.id, coalesce(o.pickup_code, ''),
       (o.total_idr / 10000) * 10,
       'Order selesai ' || coalesce(o.pickup_code, '')
  from public.orders o
 where o.status = 'completed'
   and (o.total_idr / 10000) * 10 > 0
   and not exists (
        select 1 from public.exp_ledger l
         where l.order_id = o.id and l.exp > 0
   )
on conflict (order_id) where exp > 0 and order_id is not null
do nothing;

-- Sinkronkan ulang saldo dari buku besar (sumber kebenaran).
update public.profiles p
   set exp_balance = greatest(0, coalesce((
        select sum(l.exp) from public.exp_ledger l where l.user_id = p.id
   ), 0));

-- Samakan balance_after baris yang masih 0 (riwayat lama).
update public.exp_ledger l
   set balance_after = greatest(0, coalesce((
        select sum(x.exp) from public.exp_ledger x
         where x.user_id = l.user_id
           and (x.created_at, x.id) <= (l.created_at, l.id)
   ), 0))
 where l.balance_after = 0;

commit;
