-- Nomor HP pembeli untuk layar detail pesanan seller.
-- RPC dibatasi: pembeli pemilik order, seller/admin, atau developer.
-- Ditulis plpgsql agar aman bila tabel user_phones belum ada di lingkungan lokal.
create or replace function public.order_buyer_phone(p_order_id uuid)
returns text
language plpgsql
security definer
stable
set search_path = ''
as $$
begin
    if to_regclass('public.user_phones') is null then
        return null;
    end if;
    return (
        select up.phone
        from public.orders o
        join public.user_phones up on up.user_id = o.user_id
        where o.id = p_order_id
          and (o.user_id = auth.uid() or public.is_admin() or public.is_developer())
        limit 1
    );
end;
$$;

grant execute on function public.order_buyer_phone(uuid) to authenticated;
